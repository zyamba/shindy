package shindy.eventstore

import org.scalatest.freespec.AnyFreeSpec
import shindy.examples.UserService._

import scala.language.reflectiveCalls

class SimpleEventStoreTest extends AnyFreeSpec with EventStoreBehaviors {

  val recordEventStore
      : EventStore.DefinedFor[UserRecordChangeEvent, UserRecord] =
    new InMemoryEventStore[UserRecordChangeEvent, UserRecord]()

  "Using in-memory event store implementation it should" - {
    behave like typicalEventStore(recordEventStore)
  }
}
