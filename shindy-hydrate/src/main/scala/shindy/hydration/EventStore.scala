package shindy.hydration

import java.util.UUID

import scala.language.higherKinds

case class VersionedEvent[+EVENT](event: EVENT, version: Int)

trait EventStore[STATE, EVENT, F[_]] {
  def loadEvents(aggregateId: UUID, fromVersion: Option[Int] = None): fs2.Stream[F, VersionedEvent[EVENT]]

  def storeEvents(aggregateId: UUID, event: Vector[VersionedEvent[EVENT]]): F[Unit]

  /**
    * Loads latest snapshot from event store. If the event store supports snapshoting
    * it will return latest snapshot available and its version.
    *
    * @param aggregateId Aggregate ID
    * @return latest state snapshot and its version for given aggregate.
    */
  def loadLatestStateSnapshot(aggregateId: UUID): F[Option[(STATE, Int)]]

  def storeSnapshot(aggregateId: UUID, state: STATE, version: Int): F[Unit]
}
