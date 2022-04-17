package shindy.eventstore

import cats.effect._
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.Tag
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import shindy.examples.UserService._
import shindy.{EventSourced, SourcedCreation, SourcedUpdate}

import java.time.{LocalDate, ZoneId}
import java.util.{Calendar, UUID}
import scala.Function.tupled
import scala.language.reflectiveCalls

object DatabaseTest extends Tag("DatabaseTest")

trait EventStoreBehaviors
    extends Matchers
    with AsyncIOSpec
    with ScalaCheckPropertyChecks
    with Hydration[UserRecord, UserRecordChangeEvent] {
  this: AsyncFreeSpec =>

  private val snapshotIntervalValue: Int = 100

  /** Defines typical behavior of EventStore.DefinedFor and tests against given implementation.
    */
  protected def typicalEventStore(
      recordEventStore: EventStore[UserRecordChangeEvent, UserRecord, IO]
  ) = {

    def loadEvents(id: UUID) = {
      recordEventStore.loadEvents(id).compile.toList
    }

    "hydrate" taggedAs DatabaseTest in {
      val userId = UUID.randomUUID()
      val birthdate = LocalDate.of(2000, 1, 1)
      val updatedEmail = "updated@email.com"

      // store some events
      val events = Vector(
        UserCreated(userId, "test@test.com"),
        EmailUpdated(updatedEmail),
        BirthdateUpdated(birthdate)
      )
      val versionedEvents =
        events.zip(events.indices.map(_ + 1)).map(tupled(VersionedEvent.apply))

      val stateResults = for {
        _ <- recordEventStore.storeEvents(userId, versionedEvents)
        results <- hydrate[IO](userId).state().run(recordEventStore)
      } yield results

      // hydrate record
      stateResults.asserting { state =>
        // evaluate state
        state.id shouldBe userId
        state.birthdate shouldBe Some(birthdate)
        state.email shouldBe updatedEmail
      }
    }

    "store and load events with incremental version value" taggedAs DatabaseTest in {
      val userId = UUID.randomUUID()
      val program = for {
        createResult <- createNew[IO](createUser(userId, "test@gmail.com"))
          .persist()
          .run(recordEventStore)
        _ <- hydrate[IO](userId)
          .update(
            updateEmail("updated@email.com") andThen changeBirthdate(
              LocalDate.of(2000, 1, 1)
            )
          )
          .persist()
          .run(recordEventStore)
        events <- loadEvents(userId)
      } yield (createResult._1, events)

      program.asserting { case (id, events) =>
        id should equal(userId)
        events.map(_.version) should contain theSameElementsInOrderAs
          events.indices.map(_ + 1)
      }
    }

    "be able to persist new aggregate instances and apply updates" taggedAs DatabaseTest in {

      val userId = UUID.randomUUID()
      val hydratedAggregate = createNew[IO](
        createUser(userId, "test@test.com")
      ) update updateEmail("updated@email.com")

      val programResults = for {
        r <- hydratedAggregate.persist().run(recordEventStore)
        results <- loadEvents(r._1)
      } yield (r._1, r._2, results)

      programResults.asserting { case (id, state, results) =>
        id shouldBe userId
        state.email shouldBe "updated@email.com"

        results should not be empty
        results.map(_.version) shouldEqual (1 to results.size).toList
        results.map(_.event) should contain theSameElementsInOrderAs Seq(
          UserCreated(userId, "test@test.com"),
          EmailUpdated("updated@email.com")
        )
      }
    }

    "report errors when attempting to load non existent aggregate" taggedAs DatabaseTest in {

      val userId = UUID.randomUUID()

      (hydrate[IO](userId) update
        updateEmail("new@email.com"))
        .state()
        .run(recordEventStore)
        .assertThrows[Exception]
    }

    "report errors when attempting to persist non existent aggregate" taggedAs DatabaseTest in {

      val userId = UUID.randomUUID()

      (hydrate[IO](userId) update
        updateEmail("new@email.com"))
        .persist()
        .run(recordEventStore)
        .assertThrows[Exception]
    }

    "report provided error message" taggedAs DatabaseTest in {
      val errorMessage = "test error"

      val unconditionalErr: SourcedUpdate[UserRecord, Nothing, Nothing] =
        EventSourced.sourceError(errorMessage)

      val sourceError =
        createNew[IO](createUser(UUID.randomUUID(), "test@test.com")) update
          updateEmail("new@email.com") update unconditionalErr

      sourceError.state().run(recordEventStore).assertThrows[Exception]
      // TODO
      /*
            val actualMessage: String = s.left.map { err =>
              err.printStackTrace(new PrintWriter(errOut))
              errOut.flush()
              errOut.toString
            }.left.getOrElse("")
            actualMessage should include( errorMessage)
       */
    }

    "not trigger snapshot if snapshot interval not exceeded" taggedAs DatabaseTest in {
      val initial: SourcedCreation[UserRecord, UserRecordChangeEvent, UUID] =
        createUser(UUID.randomUUID(), "foo@bar.com")
      val allOps = (1 until (snapshotIntervalValue - 1)).foldLeft(
        createNew[IO](initial).map(_ => ())
      ) { (sc, n) =>
        val scUp = sc.update(updateEmail(s"updated_$n@test.com").map(_ => ()))
        scUp
      }
      val program = for {
        p <- allOps.persist().run(recordEventStore)
        snapshot <- recordEventStore.loadLatestStateSnapshot(p._1)
        events <- loadEvents(p._1)
      } yield (snapshot, events)

      program.asserting { case (snapshot, events) =>
        snapshot shouldNot be(Symbol("defined"))
        events should not be empty
      }
    }

    "trigger snapshot if snapshot interval exceeded" taggedAs DatabaseTest in {
      val initial = createUser(UUID.randomUUID(), "foo@bar.com")

      val allOps = (1 until snapshotIntervalValue)
        .foldLeft(SourcedUpdate.pure[UserRecord, UserRecordChangeEvent](())) { (sc, n) =>
          sc andThen updateEmail(s"updated_$n@test.com").map(_ => ())
        }

      val finalEmail = "final@test.com"
      val program = for {
        // this would persist event and creates a snapshot
        createdAndPersisted <- createNew[IO](initial)
          .update(allOps)
          .persist()
          .run(recordEventStore)
        id = createdAndPersisted._1
        // snapshot should have been created
        snapshotOpt <- recordEventStore.loadLatestStateSnapshot(id)
        hydratedAndUpdated <- hydrate[IO](createdAndPersisted._1)
          .update(updateEmail(finalEmail))
          .persist()
          .run(recordEventStore)
        hydratedAndUpdatedState = hydratedAndUpdated._2
        hydratedState <- hydrate[IO](id).state().run(recordEventStore)
      } yield (snapshotOpt, hydratedAndUpdatedState, hydratedState)

      program.asserting { case (snapshot, hydratedAndUpdatedState, hydratedState) =>
        snapshot should be(Symbol("defined"))
        snapshot.map(_._1) should not be equal(hydratedAndUpdatedState)

        hydratedState shouldEqual hydratedAndUpdatedState
      }
    }
  }

  override protected final def stateSnapshotInterval: Option[Int] =
    Some(snapshotIntervalValue)

  private val userRecGen = for {
    id <- arbitrary[UUID]
    email <- Gen.alphaStr.suchThat(_.nonEmpty).map(_ + "@test.com")
    arbDate <- Gen.option(
      arbitrary[Calendar]
        .map(_.toInstant.atZone(ZoneId.systemDefault()))
        .map(_.toLocalDate)
    )
  } yield UserRecord(id, email, arbDate)

  implicit val arbUserRecGen: Arbitrary[UserRecord] = Arbitrary(userRecGen)
}
