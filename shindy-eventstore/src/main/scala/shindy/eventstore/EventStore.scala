package shindy.eventstore

import java.util.UUID

import zio.Task
import zio.stream.Stream

object EventStore {
  trait Service {
    def ofType[E : zio.Tagged, S : zio.Tagged](aggregateId: String): DefinedFor[E, S]
  }

  trait DefinedFor[EVENT, STATE] {
    def loadEvents(aggregateId: UUID, fromVersion: Option[Int] = None): Task[Stream[Throwable, VersionedEvent[EVENT]]]

    def storeEvents(aggregateId: UUID, event: Vector[VersionedEvent[EVENT]]): Task[Unit]

    /**
      * Loads latest snapshot from event store. If the event store supports snapshoting
      * it will return latest snapshot available and its version.
      *
      * @param aggregateId Aggregate ID
      * @return latest state snapshot and its version for given aggregate.
      */
    def loadLatestStateSnapshot(aggregateId: UUID): Task[Option[(STATE, Int)]]

    def storeSnapshot(aggregateId: UUID, state: STATE, version: Int): Task[Int]
  }
}
