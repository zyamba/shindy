package shindy.eventstore

import cats.effect.MonadCancelThrow
import shindy.EventSourced.EventHandler
import shindy.SourcedEval

import java.util.UUID

/** Mixin to hydrate state of the aggregate from event store, execute updates and persist events in event store.
  *
  * @tparam S
  *   Type of state.
  * @tparam E
  *   Type of events.
  */
trait Hydration[S, E]:

  /** Indicates the least number of events that need to be produced in order to store a snapshot. By default state
    * snapshots are disabled.
    */
  protected def stateSnapshotInterval: Option[Int] = None

  def createNew[F[_]: MonadCancelThrow](sourcedCreation: SourcedEval[Null, S, E, UUID])(using
      eventHandler: EventHandler[S, E]
  ): Hydrated[S, E, Unit, F] = HydratedImpl.createNew(sourcedCreation, stateSnapshotInterval)

  def hydrate[F[_]: MonadCancelThrow](aggregateId: UUID)(using
      eventHandler: EventHandler[S, E],
      compiler: fs2.Compiler[F, F]
  ): Hydrated[S, E, Unit, F] = HydratedImpl.hydrate(aggregateId, stateSnapshotInterval)
