package shindy.eventstore

import cats.Monad
import cats.data.ReaderT
import cats.effect.{MonadCancel, MonadCancelThrow}
import cats.syntax.all._
import shindy.EventSourced.EventHandler
import shindy.{EventSourced, SourcedCreation, SourcedUpdate}

import java.util.UUID

private[shindy] object HydratedImpl {

  def createNew[STATE, EVENT, F[_]: MonadCancelThrow](
      sc: SourcedCreation[STATE, EVENT, UUID],
      snapshotInterval: Option[Int] = None
  )(implicit
      eventHandler: EventHandler[STATE, EVENT]
  ): Hydrated[STATE, EVENT, Unit, F] =
    new HydratedImpl(
      ReaderT.pure(sc.map(id => (id, 0, 0))),
      SourcedUpdate.pure(()),
      snapshotInterval
    )

  def hydrate[STATE, EVENT, F[_]: MonadCancelThrow](
      aggregateId: UUID,
      snapshotInterval: Option[Int] = None
  )(implicit
      eventHandler: EventHandler[STATE, EVENT],
      evCompiler: fs2.Compiler[F, F]
  ): Hydrated[STATE, EVENT, Unit, F] = new HydratedImpl(
    ReaderT { es: EventStore[EVENT, STATE, F] =>
      es.loadLatestStateSnapshot(aggregateId)
        .map { sOut =>
          (sOut, es.loadEvents(aggregateId, sOut.map(_._2)))
        }
        .flatMap { case (snapshot, events) =>
          val initialState = snapshot.map(_._1)
          val snapshotVer = snapshot.map(_._2)

          val foldedState = events
            .fold((initialState, 0)) { case ((s, _), ev) =>
              eventHandler(s, ev.event).some -> ev.version
            }
          val value = foldedState.map { case (s, ver) =>
            EventSourced
              .sourceState[STATE, EVENT](
                Either.fromOption(s, s"Unable to load state for aggregate with ID=$aggregateId")
              )
              .map(_ => (aggregateId, snapshotVer.getOrElse(0), ver))
          }
          value.compile.toList.map { x =>
            Either.fromOption(x.headOption, new Exception("No such aggregate"))
          }.rethrow
        }
    },
    SourcedUpdate.pure(()),
    snapshotInterval
  )
}

/** @param scLoad
  *   Loads SourcedCreation which returns tuple of aggregateID, snapshot version and latest event version
  * @param sourcedUpdate
  *   SourcedUpdate to be applied to loaded state. Only events produced by this object are logged and will be persisted
  *   when persist method is called.
  * @param snapshotInterval
  *   Interval that defines how often state snapshot is performed.
  * @tparam STATE
  *   Type of the state
  * @tparam EVENT
  *   Type of the event
  * @tparam A
  *   Output value type
  */
private class HydratedImpl[STATE, EVENT, A, F[_]: MonadCancelThrow](
    scLoad: ReaderT[F, EventStore[EVENT, STATE, F], SourcedCreation[STATE, EVENT, (UUID, Int, Int)]],
    sourcedUpdate: SourcedUpdate[STATE, EVENT, A],
    snapshotInterval: Option[Int]
) extends Hydrated[STATE, EVENT, A, F] {
  override def map[B](f: A => B): Hydrated[STATE, EVENT, B, F] =
    new HydratedImpl[STATE, EVENT, B, F](scLoad, sourcedUpdate.map(f), snapshotInterval)

  override def state(): ReaderT[F, EventStore[EVENT, STATE, F], STATE] = scLoad
    .map(_.andThen(sourcedUpdate))
    .map(_.state.leftMap(new Exception(_)))
    .flatMapF(MonadCancel[F].pure(_).rethrow)

  override def update[B](f: A => SourcedUpdate[STATE, EVENT, B]): Hydrated[STATE, EVENT, B, F] =
    new HydratedImpl(scLoad, sourcedUpdate.andThen(f), snapshotInterval)

  private val noopF: F[Unit] = Monad[F].pure(())

  override def persist(): ReaderT[F, EventStore[EVENT, STATE, F], (UUID, STATE, A)] = {
    val runProgram = scLoad.map { sourcedLoad =>
      sourcedLoad
        .andThen { idAndVer =>
          sourcedUpdate.map(a => (idAndVer._1, idAndVer._2, idAndVer._3, a))
        }
        .run
        .leftMap(new Exception(_))
        .map { case (newEvents, newState, (aggId, snapshotVersion, initialVersion, aOut)) =>
          val versionedEvents = newEvents
            .zip(LazyList.from(initialVersion + 1))
            .map(Function tupled VersionedEvent.apply)
          (aggId, versionedEvents, newState, snapshotVersion, aOut)
        }
    }
    runProgram.flatMap { programResults =>
      ReaderT { es: EventStore[EVENT, STATE, F] =>
        programResults.traverse { case (aggId, events, newState, snapshotVersion, aOut) =>
          for {
            _ <- es.storeEvents(aggId, events)
            _ <- events.lastOption
              .map(_.version)
              .map { lastEventVersion =>
                maybeDoSnapshot(snapshotVersion, lastEventVersion, aggId, newState, es)
              }
              .getOrElse(noopF)
          } yield (aggId, newState, aOut)
        }
      }.flatMapF(MonadCancel[F].pure(_).rethrow)
    }
  }

  private def maybeDoSnapshot(
      lastSnapshotVer: Int,
      lastVersion: Int,
      aggregateId: UUID,
      state: STATE,
      store: EventStore[EVENT, STATE, F]
  ): F[Unit] =
    snapshotInterval
      .map { interval =>
        if ((lastVersion - lastSnapshotVer) >= interval) {
          store.storeSnapshot(aggregateId, state, lastVersion).map(_ => ())
        } else {
          noopF
        }
      }
      .getOrElse(noopF)
}
