package shindy.eventstore

import fs2.Stream

import java.util.UUID

trait EventStore[EVENT, STATE, F[_]] {

  def loadEvents(aggregateId: UUID, fromVersion: Option[Int] = None): Stream[F, VersionedEvent[EVENT]]

  def storeEvents(aggregateId: UUID, event: Vector[VersionedEvent[EVENT]]): F[Unit]

  /** Loads latest snapshot from event store. If the event store supports snapshoting it will return latest snapshot
    * available and its version.
    *
    * @param aggregateId
    *   Aggregate ID
    * @return
    *   latest state snapshot and its version for given aggregate.
    */
  def loadLatestStateSnapshot(aggregateId: UUID): F[Option[(STATE, Int)]]

  def storeSnapshot(aggregateId: UUID, state: STATE, version: Int): F[Int]
}
