package shindy

import java.util.UUID

import cats.Monad
import shindy.EventSourced.EventHandler

import scala.language.higherKinds

trait Hydration[STATE, EVENT] {
  def createNew[F[_] : Monad](
    sourcedCreation: SourcedCreation[STATE, EVENT, UUID]
  ): Hydrated[STATE, EVENT, UUID, F] = Hydrated.createNew(sourcedCreation)

  def hydrate[F[_] : Monad](aggregateId: UUID)(
    implicit eventHandler: EventHandler[STATE, EVENT],
    streamCompiler: fs2.Stream.Compiler[F, F],
  ): Hydrated[STATE, EVENT, Unit, F] = Hydrated.hydrate(aggregateId)
}
