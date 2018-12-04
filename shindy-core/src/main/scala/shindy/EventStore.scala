package shindy

import java.util.UUID

import scala.language.higherKinds


trait EventStore[EVENT, F[_]] {
  def loadEvents(aggregateId: UUID): fs2.Stream[F, EVENT]

  def storeEvents(aggregateId: UUID, event: Vector[EVENT]): F[Unit]
}
