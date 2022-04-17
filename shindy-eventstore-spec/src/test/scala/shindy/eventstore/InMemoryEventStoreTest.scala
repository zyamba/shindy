package shindy.eventstore

import cats.effect.IO
import org.scalatest.freespec.AsyncFreeSpec
import shindy.examples.UserService._

import scala.language.reflectiveCalls

class InMemoryEventStoreshiTest extends AsyncFreeSpec with EventStoreBehaviors {

  val recordEventStore
      : EventStore[UserRecordChangeEvent, UserRecord, IO] =
    new InMemoryEventStore[UserRecordChangeEvent, UserRecord]()

  "Using in-memory event store implementation it should" - {
    behave like typicalEventStore(recordEventStore)
  }
}
