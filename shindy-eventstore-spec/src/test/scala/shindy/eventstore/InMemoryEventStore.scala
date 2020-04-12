package shindy.eventstore

import java.util.UUID

import zio.{Task, stream}

import scala.collection.mutable
import scala.language.reflectiveCalls

private class InMemoryEventStore[EVENT, STATE](
      val eventsStore: mutable.Map[UUID, Vector[
        VersionedEvent[EVENT]
      ]] = mutable.Map.empty[UUID, Vector[VersionedEvent[EVENT]]],
      val stateSnapshot: mutable.Map[UUID, (STATE, Int)] =
        mutable.Map.empty[UUID, (STATE, Int)]
  ) extends EventStore.DefinedFor[EVENT, STATE] {

    override def loadEvents(
        aggregateId: UUID,
        fromVersion: Option[Int]
    ): Task[stream.Stream[Throwable, VersionedEvent[EVENT]]] = Task {
      stream.Stream.fromIterable {
        val minVersion = fromVersion.getOrElse(0)
        eventsStore(aggregateId).filter(_.version >= minVersion)
      }
    }

    override def storeEvents(
        aggregateId: UUID,
        events: Vector[VersionedEvent[EVENT]]
    ): Task[Unit] = Task {
      val storedEvents = eventsStore.getOrElseUpdate(aggregateId, Vector.empty)
      val products: Vector[VersionedEvent[EVENT]] =
        storedEvents ++ events
      eventsStore.update(aggregateId, products)
    }

    override def loadLatestStateSnapshot(
        aggregateId: UUID
    ): Task[Option[(STATE, Int)]] = Task {
      stateSnapshot.get(aggregateId)
    }

    override def storeSnapshot(
        aggregateId: UUID,
        state: STATE,
        version: Int
    ): Task[Int] = Task {
      stateSnapshot.update(aggregateId, state -> version)
      1
    }
  }