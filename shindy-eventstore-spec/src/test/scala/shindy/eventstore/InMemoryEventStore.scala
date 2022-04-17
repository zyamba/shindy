package shindy.eventstore

import cats.effect.IO

import java.util.UUID
import scala.collection.mutable
import scala.language.reflectiveCalls

private class InMemoryEventStore[EVENT, STATE](
      val eventsStore: mutable.Map[UUID, Vector[
        VersionedEvent[EVENT]
      ]] = mutable.Map.empty[UUID, Vector[VersionedEvent[EVENT]]],
      val stateSnapshot: mutable.Map[UUID, (STATE, Int)] =
        mutable.Map.empty[UUID, (STATE, Int)]
  ) extends EventStore[EVENT, STATE, IO] {

  override def loadEvents(aggregateId: UUID, fromVersion: Option[Int]): fs2.Stream[IO, VersionedEvent[EVENT]] = {
    fs2.Stream.evalSeq( IO {
      val minVersion = fromVersion.getOrElse(0)
      eventsStore(aggregateId).filter(_.version >= minVersion)
    })
  }

  override def storeEvents(aggregateId: UUID, events: Vector[VersionedEvent[EVENT]]): IO[Unit] = IO {
      val storedEvents = eventsStore.getOrElseUpdate(aggregateId, Vector.empty)
      val products: Vector[VersionedEvent[EVENT]] =
        storedEvents ++ events
      eventsStore.update(aggregateId, products)
    }

    override def loadLatestStateSnapshot(
        aggregateId: UUID
    ): IO[Option[(STATE, Int)]] = IO {
      stateSnapshot.get(aggregateId)
    }

    override def storeSnapshot(
        aggregateId: UUID,
        state: STATE,
        version: Int
    ): IO[Int] = IO {
      stateSnapshot.update(aggregateId, state -> version)
      1
    }
  }