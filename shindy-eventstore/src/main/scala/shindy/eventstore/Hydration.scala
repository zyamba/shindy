package shindy.eventstore

import cats.effect.MonadCancelThrow
import shindy.EventSourced.EventHandler
import shindy.SourcedCreation

import java.util.UUID

/** Mixin to hydrate state of the aggregate from event store, execute updates and persist events in event store.
  *
  * @tparam STATE
  *   Type of state.
  * @tparam EVENT
  *   Type of events.
  */
trait Hydration[STATE, EVENT] {

  /** Indicates the least number of events that need to be produced in order to store a snapshot. By default state
    * snapshots are disabled.
    */
  protected def stateSnapshotInterval: Option[Int] = None

  def createNew[F[_]: MonadCancelThrow](sourcedCreation: SourcedCreation[STATE, EVENT, UUID])(implicit
      eventHandler: EventHandler[STATE, EVENT]
  ): Hydrated[STATE, EVENT, Unit, F] = HydratedImpl.createNew(sourcedCreation, stateSnapshotInterval)

  def hydrate[F[_]: MonadCancelThrow](aggregateId: UUID)(implicit
      eventHandler: EventHandler[STATE, EVENT],
      compiler: fs2.Compiler[F, F]
  ): Hydrated[STATE, EVENT, Unit, F] = HydratedImpl.hydrate(aggregateId, stateSnapshotInterval)
}
