package shindy.eventstore

import java.util.UUID

import cats.syntax.either._
import cats.syntax.option._
import shindy.EventSourced.EventHandler
import shindy.{EventSourced, SourcedCreation, SourcedUpdate}
import zio.{RIO, ZIO}

import scala.language.reflectiveCalls

private[shindy] object HydratedZ {

  def createNew[STATE : zio.Tagged, EVENT : zio.Tagged](
    sc: SourcedCreation[STATE, EVENT, UUID],
    snapshotInterval: Option[Int] = None
  )(
    implicit eventHandler: EventHandler[STATE, EVENT]
  ): Hydrated[STATE, EVENT, Unit] =
    new HydratedZ(
      ZIO(sc.map(id => (id, 0, 0))),
      SourcedUpdate.pure(()),
      snapshotInterval
    )

  def hydrate[STATE : zio.Tagged, EVENT : zio.Tagged](
    aggregateId: UUID,
    snapshotInterval: Option[Int] = None
  )(
    implicit eventHandler: EventHandler[STATE, EVENT]
  ): Hydrated[STATE, EVENT, Unit] = new HydratedZ(
    ZIO.accessM[EventStore[EVENT, STATE]] { hasES =>
      val es = hasES.get
      es.loadLatestStateSnapshot(aggregateId)
        .flatMap { sOut =>
          es.loadEvents(aggregateId, sOut.map(_._2))
            .map(events => (sOut, events))
        }
        .flatMap { case (snapshot, events) =>
          val initialState = snapshot.map(_._1)
          val snapshotVer = snapshot.map(_._2)

          events
            .fold((initialState, 0)) { case ((s, _), ev) =>
              eventHandler(s, ev.event).some -> ev.version
            }.map { case (s, ver) =>
            EventSourced.sourceState(Either.fromOption(s,
              s"Unable to load state for aggregate with ID=$aggregateId")
            ).map(_ => (aggregateId, snapshotVer.getOrElse(0), ver))
          }
        }
    },
    SourcedUpdate.pure(()),
    snapshotInterval
  )
}

/**
  *
  * @param scLoad Loads SourcedCreation which returns tuple of aggregateID,
  *               snapshot version and latest event version
  * @param sourcedUpdate SourcedUpdate to be applied to loaded state. Only events produced by this
  *                      object are logged and will be persisted when persist method is called.
  * @param snapshotInterval Interval that defines how often state snapshot is performed.
  * @tparam STATE Type of the state
  * @tparam EVENT Type of the event
  * @tparam A     Output value type
  */
private class HydratedZ[STATE: zio.Tag, EVENT: zio.Tag, A](
  scLoad: RIO[EventStore[EVENT, STATE], SourcedCreation[STATE, EVENT, (UUID, Int, Int)]],
  sourcedUpdate: SourcedUpdate[STATE, EVENT, A],
  snapshotInterval: Option[Int]
) extends Hydrated[STATE, EVENT, A] {
  override def map[B](f: A => B): Hydrated[STATE, EVENT, B] =
    new HydratedZ[STATE, EVENT, B](scLoad, sourcedUpdate.map(f), snapshotInterval)

  override def state(): RIO[EventStore[EVENT, STATE], STATE] =
    scLoad.flatMap { sc =>
      RIO.accessM[EventStore[EVENT, STATE]] { _ =>
        RIO.fromEither(sc.andThen(sourcedUpdate).state.left.map(new Exception(_)))
      }
    }
  override def update[B](f: A => SourcedUpdate[STATE, EVENT, B]): Hydrated[STATE, EVENT, B] =
    new HydratedZ(scLoad, sourcedUpdate.andThen(f), snapshotInterval)

  override def persist(): RIO[EventStore[EVENT, STATE], (UUID, STATE, A)] = {
    scLoad.flatMap { sourcedLoad =>
      val runProgram = RIO.fromEither {
        sourcedLoad.andThen { idAndVer =>
          sourcedUpdate.map(a => (idAndVer._1, idAndVer._2, idAndVer._3, a))
        }.run.left.map(new Exception(_))
      }.map { case (newEvents, newState, (aggId, snapshotVersion, initialVersion, aOut)) =>
        val versionedEvents = newEvents.zip(Stream.from(initialVersion + 1))
          .map(Function tupled VersionedEvent.apply)
        (aggId, versionedEvents, newState, snapshotVersion, aOut)
      }
      for {
        es <- RIO.environment[EventStore[EVENT, STATE]]
        (aggId, events, newState, snapshotVersion, aOut) <- runProgram
        _ <- es.get.storeEvents(aggId, events)
        _ <- events.lastOption.map(_.version)
          .map { lastEventVersion =>
            maybeDoSnapshot(snapshotVersion, lastEventVersion, aggId, newState)
          }
          .getOrElse(RIO.unit)
      } yield (aggId, newState, aOut)
    }
  }

  private def maybeDoSnapshot(
    lastSnapshotVer: Int,
    lastVersion: Int,
    aggregateId: UUID,
    state: STATE
  ): RIO[EventStore[EVENT, STATE], Unit] =
    snapshotInterval.map { interval =>
      if ((lastVersion - lastSnapshotVer) >= interval) {
        RIO.accessM[EventStore[EVENT, STATE]] { hasES =>
          hasES.get.storeSnapshot(aggregateId, state, lastVersion)
        }.map(_ => ())
      } else RIO.unit
    }.getOrElse(RIO.unit)
}