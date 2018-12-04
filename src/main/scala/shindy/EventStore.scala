package shindy

import java.util.UUID

import cats.effect.IO

import scala.language.higherKinds


trait EventStore[EVENT] {
  def loadEvents(aggregateId: UUID): fs2.Stream[IO, EVENT]

  def storeEvents(aggregateId: UUID, event: Vector[EVENT]): IO[Unit]
}
