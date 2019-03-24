package shindy.hydration

import java.time.LocalDate
import java.util.UUID

import cats.effect.IO
import org.scalatest.{FreeSpec, Matchers}
import shindy.examples.UserService._

import scala.Function.tupled
import scala.collection.mutable

class HydratedTest extends FreeSpec with Matchers with Hydration[UserRecord, UserRecordChangeEvent] {

  class InMemoryEventDatabase(val store: mutable.Map[UUID, Vector[VersionedEvent[UserRecordChangeEvent]]] = mutable.Map.empty)
    extends EventStore[UserRecord, UserRecordChangeEvent, IO] {

    override def loadEvents(aggregateId: UUID, fromVersion: Option[Int] = None): fs2.Stream[IO, VersionedEvent[UserRecordChangeEvent]] =
      fs2.Stream.fromIterator[IO, VersionedEvent[UserRecordChangeEvent]]{
        val minVersion = fromVersion.getOrElse(0)
        store(aggregateId).iterator.filter(_.version >= minVersion)
      }

    override def storeEvents(aggregateId: UUID, events: Vector[VersionedEvent[UserRecordChangeEvent]]): IO[Unit] =  IO {
      val storedEvents = store.getOrElseUpdate(aggregateId, Vector.empty)
      val products: Vector[VersionedEvent[UserRecordChangeEvent]] = storedEvents ++ events
      store.update(aggregateId, products)
    }

    override def loadLatestStateSnapshot(aggregateId: UUID): IO[Option[(UserRecord, Int)]] = IO.pure(None)

    override def storeSnapshot(aggregateId: UUID, state: UserRecord, version: Int): IO[Unit] = IO.pure(())
  }

  "Methods tests" - {
    val eventStore: EventStore[UserRecord, UserRecordChangeEvent, IO] = new InMemoryEventDatabase()
    "hydrate" in {
      val userId = UUID.randomUUID()
      val birthdate = LocalDate.of(2000, 1, 1)
      val updatedEmail = "updated@email.com"

      // store some events
      val events = Vector(UserCreated(userId, "test@test.com"), EmailUpdated(updatedEmail),
        BirthdateUpdated(birthdate))
      val versionedEvents = events.zip(Stream.from(1)).map(tupled(VersionedEvent.apply))
      eventStore.storeEvents(userId, versionedEvents) unsafeRunSync()

      // hydrate record
      val stateRun = hydrate[IO](userId).state(eventStore)

      // evaluate state
      val results = stateRun unsafeRunSync()
      results should be('right)
      val state = results.right.get

      state.id shouldBe userId
      state.birthdate shouldBe Some(birthdate)
      state.email shouldBe updatedEmail
    }

    "events versions should be increasing" in {
      val userId = UUID.randomUUID()
      createNew[IO](createUser(userId, "test@gmail.com"))
        .persist(eventStore).unsafeRunSync() should be('right)

      hydrate[IO](userId).update(
        updateEmail("updated@email.com") andThen changeBirthdate(LocalDate.of(2000, 1, 1))
      ).persist(eventStore).unsafeRunSync() should be('right)

      val events  = eventStore.loadEvents(userId).compile.toList.unsafeRunSync()
      events.map(_.version) should contain theSameElementsInOrderAs Stream.from(0).take(events.size)

    }

    "createNew" in {

      val userId = UUID.randomUUID()
      val hydratedAggregate = createNew[IO](
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
      val nonExistentRecord = hydrate[IO](userId) update
        updateEmail("new@email.com")

      an [Exception] shouldBe thrownBy (nonExistentRecord.state(eventStore).unsafeRunSync())
      an [Exception] shouldBe thrownBy (nonExistentRecord.persist(eventStore).unsafeRunSync())
    }
  }
}
