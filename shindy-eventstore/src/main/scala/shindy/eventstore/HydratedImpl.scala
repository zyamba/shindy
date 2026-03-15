package shindy.eventstore

import cats.Monad
import cats.data.ReaderT
import cats.effect.{MonadCancel, MonadCancelThrow}
import cats.syntax.all.*
import shindy.EventSourced.EventHandler
import shindy.{EventSourced, SourcedEval}

import java.util.UUID

private[shindy] object HydratedImpl:

  def createNew[S, E, F[_]: MonadCancelThrow](
      sc: SourcedEval[Null, S, E, UUID],
      snapshotInterval: Option[Int] = None
  )(using
      eventHandler: EventHandler[S, E]
  ): Hydrated[S, E, Unit, F] =
    new HydratedImpl(
      ReaderT.pure(sc.map(id => (id, 0, 0))),
      SourcedEval.pure(()),
      snapshotInterval
    )

  def hydrate[S, E, F[_]: MonadCancelThrow](
      aggregateId: UUID,
      snapshotInterval: Option[Int] = None
  )(using
      eventHandler: EventHandler[S, E],
      evCompiler: fs2.Compiler[F, F]
  ): Hydrated[S, E, Unit, F] = new HydratedImpl(
    ReaderT { (es: EventStore[E, S, F]) =>
      es.loadLatestStateSnapshot(aggregateId)
        .map { sOut =>
          (sOut, es.loadEvents(aggregateId, sOut.map(_._2)))
        }
        .flatMap { case (snapshot, events) =>
          val initialState = snapshot.map(_._1)
          val snapshotVer = snapshot.map(_._2)

          val foldedState = events
            .fold((initialState, 0)) { case ((s, _), ev) =>
              eventHandler(s.orNull, ev.event).some -> ev.version
            }
          val value: fs2.Stream[F, SourcedEval[Null, S, E, (UUID, Int, Int)]] =
            foldedState.map { case (s, ver) =>
              SourcedEval
                .newFromState[S, E](
                  Either.fromOption(s, s"Unable to load state for aggregate with ID=$aggregateId")
                )
                .map(_ => (aggregateId, snapshotVer.getOrElse(0), ver))
            }
          value.compile.toList.map { x =>
            Either.fromOption(x.headOption, new Exception("No such aggregate"))
          }.rethrow
        }
    },
    SourcedEval.pure(()),
    snapshotInterval
  )

/** @param scLoad
  *   Loads SourcedEval which returns tuple of aggregateID, snapshot version and latest event version
  * @param sourcedUpdate
  *   SourcedUpdate to be applied to loaded state. Only events produced by this object are logged and will be persisted
  *   when persist method is called.
  * @param snapshotInterval
  *   Interval that defines how often state snapshot is performed.
  * @tparam S
  *   Type of the state
  * @tparam E
  *   Type of the event
  * @tparam A
  *   Output value type
  */
private class HydratedImpl[S, E, A, F[_]: MonadCancelThrow](
    scLoad: ReaderT[F, EventStore[E, S, F], SourcedEval[Null, S, E, (UUID, Int, Int)]],
    sourcedUpdate: SourcedEval[S, S, E, A],
    snapshotInterval: Option[Int]
) extends Hydrated[S, E, A, F]:
  override def map[B](f: A => B): Hydrated[S, E, B, F] =
    new HydratedImpl[S, E, B, F](scLoad, sourcedUpdate.map(f), snapshotInterval)

  override def state(): ReaderT[F, EventStore[E, S, F], S] = scLoad
    .map(_.andThen(sourcedUpdate))
    .map(_.state(null).leftMap(new Exception(_)))
    .flatMapF(MonadCancel[F].pure(_).rethrow)

  override def update[B](f: A => SourcedEval[S, S, E, B]): Hydrated[S, E, B, F] =
    new HydratedImpl(scLoad, sourcedUpdate.andThen(f), snapshotInterval)

  private val noopF: F[Unit] = Monad[F].pure(())

  override def persist(): ReaderT[F, EventStore[E, S, F], (UUID, S, A)] =
    val runProgram = scLoad.map { sourcedLoad =>
      sourcedLoad
        .andThen { idAndVer =>
          sourcedUpdate.map(a => (idAndVer._1, idAndVer._2, idAndVer._3, a))
        }
        .run
        .leftMap(new Exception(_))
        .map { case (newEvents, newState, (aggId, snapshotVersion, initialVersion, aOut)) =>
          val versionedEvents: Vector[VersionedEvent[E]] = newEvents
            .zip(LazyList.from(initialVersion + 1))
            .map { case (ev, v) => VersionedEvent.apply(ev, v) }
          (aggId, versionedEvents, newState, snapshotVersion, aOut)
        }
    }
    runProgram.flatMap { programResults =>
      ReaderT { (es: EventStore[E, S, F]) =>
        programResults.traverse { case (aggId, events, newState, snapshotVersion, aOut) =>
          for
            _ <- es.storeEvents(aggId, events)
            _ <- events.lastOption
              .map(_.version)
              .map { lastEventVersion =>
                maybeDoSnapshot(snapshotVersion, lastEventVersion, aggId, newState, es)
              }
              .getOrElse(noopF)
          yield (aggId, newState, aOut)
        }
      }.flatMapF(MonadCancel[F].pure(_).rethrow)
    }

  private def maybeDoSnapshot(
      lastSnapshotVer: Int,
      lastVersion: Int,
      aggregateId: UUID,
      state: S,
      store: EventStore[E, S, F]
  ): F[Unit] =
    snapshotInterval
      .map { interval =>
        if (lastVersion - lastSnapshotVer) >= interval then
          store.storeSnapshot(aggregateId, state, lastVersion).map(_ => ())
        else noopF
      }
      .getOrElse(noopF)
