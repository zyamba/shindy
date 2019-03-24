package shindy.hydration

import java.util.UUID

import cats.Monad
import shindy.EventSourced.EventHandler
import shindy.SourcedCreation

import scala.language.higherKinds

/**
  * Provides ability to hydrate state of the aggregate from event store, update and persist events in event store.
  *
  * @tparam STATE Type of state.
  * @tparam EVENT Type of events.
  */
trait Hydration[STATE, EVENT] {
  def createNew[F[_] : Monad](
    sourcedCreation: SourcedCreation[STATE, EVENT, UUID]
  ): Hydrated[STATE, EVENT, UUID, F] = Hydrated.createNew(sourcedCreation)

  def hydrate[F[_] : Monad](aggregateId: UUID)(
    implicit eventHandler: EventHandler[STATE, EVENT],
    streamCompiler: fs2.Stream.Compiler[F, F],
  ): Hydrated[STATE, EVENT, Unit, F] = Hydrated.hydrate(aggregateId)

  def persist[F[_], A](h: Hydrated[STATE, EVENT, A, F],
    eventStore: EventStore[STATE, EVENT, F]): F[Either[String, (UUID, STATE, A)]] = h.persist(eventStore)
}
