package shindy

import java.time.LocalDate
import java.util.UUID

import cats.effect.IO
import org.scalatest.{FreeSpec, Matchers}

import scala.collection.mutable

class HydratableTest extends FreeSpec with Matchers {

  import BusinessDomain._

  class InMemoryEventDatabase(val store: mutable.Map[UUID, Vector[UserRecordChangeEvent]] = mutable.Map.empty) extends EventStore[UserRecordChangeEvent, IO] {

    override def loadEvents(aggregateId: UUID): fs2.Stream[IO, BusinessDomain.UserRecordChangeEvent] =
      fs2.Stream.fromIterator[IO, UserRecordChangeEvent](store(aggregateId).iterator)

    override def storeEvents(aggregateId: UUID, events: Vector[BusinessDomain.UserRecordChangeEvent]): IO[Unit] = IO {
      val storedEvents = store.getOrElseUpdate(aggregateId, Vector.empty)
      store.update(aggregateId, storedEvents ++ events)
    }

  }

  "Methods tests" - {
    val eventStore = new InMemoryEventDatabase()
    "hydrate" in {
      val userId = UUID.randomUUID()
      val birthdate = LocalDate.of(2000, 1, 1)
      val updatedEmail = "updated@email.com"

      // store some events
      val events = Vector(UserCreated(userId, "test@test.com"), EmailUpdated(updatedEmail),
        BirthdateUpdated(birthdate))
      eventStore.storeEvents(userId, events) unsafeRunSync()

      // hydrate record
      val stateRun = Hydratable.hydrate[UserRecord, UserRecordChangeEvent, IO](userId).state(eventStore)

      // evaluate state
      val results = stateRun unsafeRunSync()
      results should be('right)
      val state = results.right.get

      state.id shouldBe userId
      state.birthdate shouldBe Some(birthdate)
      state.email shouldBe updatedEmail
    }

    "createNew" in {

      val userId = UUID.randomUUID()
      val hydratedAggregate = Hydratable.createNew[UserRecord, UserRecordChangeEvent, IO](
        createUser(userId, "test@test.com")
      ) update updateEmail("updated@email.com")

      val value = hydratedAggregate.persist(eventStore).unsafeRunSync()
      value should be('right)

      val (id, state, _) = value.right.get
      id shouldBe userId
      state.email shouldBe "updated@email.com"

      val results = eventStore.loadEvents(id).compile.toList unsafeRunSync()
      println(results)
    }

    "attempt to load non existent aggregate should report errors" in {

      val userId = UUID.randomUUID()
      val nonExistentRecord = Hydratable.hydrate[UserRecord, UserRecordChangeEvent, IO](userId) update
        updateEmail("new@email.com")

      an [Exception] shouldBe thrownBy (nonExistentRecord.state(eventStore).unsafeRunSync())
      an [Exception] shouldBe thrownBy (nonExistentRecord.persist(eventStore).unsafeRunSync())
    }
  }
}