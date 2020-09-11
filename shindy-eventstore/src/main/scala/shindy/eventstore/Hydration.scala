package shindy.eventstore

import java.util.UUID

import shindy.EventSourced.EventHandler
import shindy.SourcedCreation

import scala.language.higherKinds

/**
  * Mixin to hydrate state of the aggregate from event store,
  * execute updates and persist events in event store.
  *
  * @tparam STATE Type of state.
  * @tparam EVENT Type of events.
  */
trait Hydration[STATE, EVENT] {
  /**
    * Indicates the least number of events that need to be produced in order
    * to store a snapshot. By default state snapshots are disabled.
    */
  protected def stateSnapshotInterval: Option[Int] = None

  def createNew(sourcedCreation: SourcedCreation[STATE, EVENT, UUID])(
    implicit eventHandler: EventHandler[STATE, EVENT],
    classTagEvent: zio.Tag[EVENT],
    classTagState: zio.Tag[STATE]
  ): Hydrated[STATE, EVENT, Unit] = HydratedZ.createNew(sourcedCreation, stateSnapshotInterval)

  def hydrate(aggregateId: UUID)(
    implicit eventHandler: EventHandler[STATE, EVENT],
    classTagEvent: zio.Tag[EVENT],
    classTagState: zio.Tag[STATE]
  ): Hydrated[STATE, EVENT, Unit] = HydratedZ.hydrate(aggregateId, stateSnapshotInterval)
}
