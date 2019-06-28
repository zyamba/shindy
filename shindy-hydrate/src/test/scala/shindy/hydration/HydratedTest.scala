package shindy.hydration

import java.time.{LocalDate, ZoneId}
import java.util.{Calendar, UUID}

import cats.effect.IO
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary.arbitrary
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.{FreeSpec, Matchers}
import shindy.compat._
import shindy.{SourcedCreation, SourcedUpdate}
import shindy.examples.UserService._

import scala.Function.tupled
import scala.collection.mutable
import scala.language.reflectiveCalls

class HydratedTest extends FreeSpec with Matchers with Hydration[UserRecord, UserRecordChangeEvent]
  with ScalaCheckDrivenPropertyChecks {

  private val snapshotInterval = 100

  override protected val stateSnapshotInterval: Option[Int] = Some(snapshotInterval)

  private val userRecGen = for {
    id <- arbitrary[UUID]
    email <- Gen.alphaStr.suchThat(_.nonEmpty).map(_ + "@test.com")
    arbDate <- Gen.option(arbitrary[Calendar].map(_.toInstant.atZone(ZoneId.systemDefault())).map(_.toLocalDate))
  } yield UserRecord(id, email, arbDate)

  implicit val arbUserRecGen: Arbitrary[UserRecord] = Arbitrary(userRecGen)

  class InMemoryEventStore(
    val eventsStore: mutable.Map[UUID, Vector[VersionedEvent[UserRecordChangeEvent]]] = mutable.Map.empty,
    val stateSnapshot: mutable.Map[UUID, (UserRecord, Int)] = mutable.Map.empty
  )
    extends EventStore[UserRecord, UserRecordChangeEvent, IO] {

    override def loadEvents(aggregateId: UUID, fromVersion: Option[Int] = None): fs2.Stream[IO, VersionedEvent[UserRecordChangeEvent]] =
      fs2.Stream.fromIterator[IO, VersionedEvent[UserRecordChangeEvent]]{
        val minVersion = fromVersion.getOrElse(0)
        eventsStore(aggregateId).iterator.filter(_.version >= minVersion)
      }

    override def storeEvents(aggregateId: UUID, events: Vector[VersionedEvent[UserRecordChangeEvent]]): IO[Unit] =  IO {
      val storedEvents = eventsStore.getOrElseUpdate(aggregateId, Vector.empty)
      val products: Vector[VersionedEvent[UserRecordChangeEvent]] = storedEvents ++ events
      eventsStore.update(aggregateId, products)
    }

    override def loadLatestStateSnapshot(aggregateId: UUID): IO[Option[(UserRecord, Int)]] = IO {
      stateSnapshot.get(aggregateId)
    }

    override def storeSnapshot(aggregateId: UUID, state: UserRecord, version: Int): IO[Int] = IO {
      stateSnapshot.update(aggregateId, state -> version)
      1
    }
  }

  "Methods tests" - {
    val eventStore: EventStore[UserRecord, UserRecordChangeEvent, IO] = new InMemoryEventStore()

    "snapshots should be disabled by default" in {
      object UserHydration extends Hydration[UserRecord, UserRecordChangeEvent]{
        override def stateSnapshotInterval: Option[Int] = super.stateSnapshotInterval
      }
      UserHydration.stateSnapshotInterval shouldEqual None
    }

    "hydrate" in {
      val userId = UUID.randomUUID()
      val birthdate = LocalDate.of(2000, 1, 1)
      val updatedEmail = "updated@email.com"

      // store some events
      val events = Vector(UserCreated(userId, "test@test.com"), EmailUpdated(updatedEmail),
        BirthdateUpdated(birthdate))
      val versionedEvents = events.zip(LazyList.from(1)).map(tupled(VersionedEvent.apply))
      eventStore.storeEvents(userId, versionedEvents).unsafeRunSync()

      // hydrate record
      val stateRun = hydrate[IO](userId).state(eventStore)

      // evaluate state
      val results = stateRun.unsafeRunSync()
      results should be (Symbol("right"))
      val state = results.getOrElse(null)

      state.id shouldBe userId
      state.birthdate shouldBe Some(birthdate)
      state.email shouldBe updatedEmail
    }

    "events versions should be increasing" in {
      val userId = UUID.randomUUID()
      createNew[IO](createUser(userId, "test@gmail.com"))
        .persist(eventStore).unsafeRunSync() should be(Symbol("right"))

      hydrate[IO](userId).update(
        updateEmail("updated@email.com") andThen changeBirthdate(LocalDate.of(2000, 1, 1))
      ).persist(eventStore).unsafeRunSync() should be(Symbol("right"))

      val events  = eventStore.loadEvents(userId).compile.toList.unsafeRunSync()
      events.map(_.version) should contain theSameElementsInOrderAs LazyList.from(0).take(events.size)

    }

    "createNew" in {

      val userId = UUID.randomUUID()
      val hydratedAggregate = createNew[IO](
        createUser(userId, "test@test.com")
      ) update updateEmail("updated@email.com")

      val value = hydratedAggregate.persist(eventStore).unsafeRunSync()
      value should be(Symbol("right"))

      val (id, state, _) = value.getOrElse(null)
      id shouldBe userId
      state.email shouldBe "updated@email.com"

      val results = eventStore.loadEvents(id).compile.toList.unsafeRunSync()
      results should not be empty
      results.map(_.version) shouldEqual results.indices.toList
      results.map(_.event) should contain theSameElementsInOrderAs Seq(
        UserCreated(userId, "test@test.com"),
        EmailUpdated("updated@email.com")
      )
    }

    "attempt to load non existent aggregate should report errors" in {

      val userId = UUID.randomUUID()
      val nonExistentRecord = hydrate[IO](userId) update
        updateEmail("new@email.com")

      an [Exception] shouldBe thrownBy (nonExistentRecord.state(eventStore).unsafeRunSync())
      an [Exception] shouldBe thrownBy (nonExistentRecord.persist(eventStore).unsafeRunSync())
    }

    "persist should trigger snapshot if snapshot interval not exceeded" in {
      forAll { initialState: UserRecord =>
        val initial: SourcedCreation[UserRecord, UserRecordChangeEvent, UUID] =
          createUser(initialState.id, initialState.email)
        val allOps = (1 until snapshotInterval).foldLeft(createNew[IO](initial).map(_ => ())) { (sc, n) =>
          val scUp = sc.update(updateEmail(s"updated_$n@test.com").map(_ => ()))
          scUp
        }
        persist(allOps, eventStore).unsafeRunSync()

        val snapshot = eventStore.loadLatestStateSnapshot(initialState.id)
          .unsafeRunSync()
        snapshot shouldNot be(Symbol("defined"))

        val events = eventStore.loadEvents(initialState.id).compile.toList.unsafeRunSync()
        events should not be empty
      }
    }

    "persist should trigger snapshot if snapshot interval exceeded" in {
      forAll { initialState: UserRecord =>
        val initial = createUser(initialState.id, initialState.email)

        val allOps = (1 until snapshotInterval).foldLeft(SourcedUpdate.pure[UserRecord, UserRecordChangeEvent]()) { (sc, n) =>
          val scUp = sc.andThen(updateEmail(s"updated_$n@test.com").map(_ => ()))
          scUp
        }.andThen(
          // this operation triggers a snapshot
          changeBirthdate(LocalDate.of(1950, 1, 1))
        )
        // this would persist event and creates a snapshot
        persist(createNew[IO](initial).update(allOps), eventStore).unsafeRunSync()

        val finalEmail = "final@test.com"
        // this would simply append a single event
        val latestState = hydrate[IO](initialState.id).update(updateEmail(finalEmail))
          .persist(eventStore)
          .unsafeRunSync().map(_._2).toOption
        latestState.map(_.email) should be (Some(finalEmail))

        val snapshot = eventStore.loadLatestStateSnapshot(initialState.id)
          .unsafeRunSync()
        snapshot should be(Symbol("defined"))
        snapshot.map(_._1) should not be equal(latestState)

        val hydratedState = hydrate[IO](initialState.id).state(eventStore)
          .unsafeRunSync().toOption
        hydratedState shouldEqual latestState
      }
    }
  }
}
