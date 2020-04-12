package shindy.eventstore


import java.io.{PrintWriter, StringWriter}
import java.time.{LocalDate, ZoneId}
import java.util.{Calendar, UUID}

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.Tag
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import shindy.{EventSourced, SourcedCreation, SourcedUpdate}
import zio.{Has, RIO, stream}
import shindy.examples.UserService._

import scala.Function.tupled
import scala.language.reflectiveCalls
import cats.implicits._

import scala.collection.compat._

object DatabaseTest extends Tag("DatabaseTest")

trait EventStoreBehaviors
    extends Matchers
    with ScalaCheckDrivenPropertyChecks
    with Hydration[UserRecord, UserRecordChangeEvent] { this: AnyFreeSpec =>

  private val zioRuntime = zio.Runtime.default

  private val snapshotIntervalValue: Int = 100

  protected def typicalEventStore(
      recordEventStore: EventStore.DefinedFor[UserRecordChangeEvent, UserRecord],
  ) = {
    def runSync[A](
        stateRun: RIO[EventStore[UserRecordChangeEvent, UserRecord], A]
    ): Either[Throwable, A] = {
      zioRuntime.unsafeRunSync(stateRun.provide(Has(recordEventStore))).toEither
    }

    def loadEvents(id: UUID) = {
      runSync(recordEventStore.loadEvents(id)).flatMap(collectEvents)
        .fold(throw _, identity)
    }

    def collectEvents[E](
        events: stream.Stream[Throwable, VersionedEvent[E]]
    ): Either[Throwable, Vector[VersionedEvent[E]]] = {
      zioRuntime
        .unsafeRunSync(
          events.fold(Vector.empty[VersionedEvent[E]])(_ :+ _)
        )
        .toEither
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
        events.zip(LazyList.from(1)).map(tupled(VersionedEvent.apply))
      val task = recordEventStore.storeEvents(userId, versionedEvents)
      zioRuntime.unsafeRunSync(task)

      // hydrate record
      val stateRun = hydrate(userId).state()

      // evaluate state
      val results = runSync(stateRun)
      results should be(Symbol("right"))
      val state = results.getOrElse(null)

      state.id shouldBe userId
      state.birthdate shouldBe Some(birthdate)
      state.email shouldBe updatedEmail
    }

    "store and load events with incremental version value" taggedAs DatabaseTest in {
      val userId = UUID.randomUUID()
      val persistOp = createNew(createUser(userId, "test@gmail.com"))
        .persist()
      runSync(persistOp) should be(Symbol("right"))

      val updOp = hydrate(userId)
        .update(
          updateEmail("updated@email.com") andThen changeBirthdate(
            LocalDate.of(2000, 1, 1)
          )
        )
        .persist()
      runSync(updOp) should be(Symbol("right"))

      val events = loadEvents(userId)
      events.map(_.version) should contain theSameElementsInOrderAs LazyList
        .from(1)
        .take(events.size)
    }

    "be able to persist new aggregate instances and apply updates" taggedAs DatabaseTest in {

      val userId = UUID.randomUUID()
      val hydratedAggregate = createNew(
        createUser(userId, "test@test.com")
      ) update updateEmail("updated@email.com")

      val value = runSync(hydratedAggregate.persist())

      value should be(Symbol("right"))

      val (id, state, _) = value.getOrElse(null)
      id shouldBe userId
      state.email shouldBe "updated@email.com"

      val results = loadEvents(id)
      results should not be empty
      results.map(_.version) shouldEqual (1 to results.size).toList
      results.map(_.event) should contain theSameElementsInOrderAs Seq(
        UserCreated(userId, "test@test.com"),
        EmailUpdated("updated@email.com")
      )
    }

    "report errors when attempting to load non existent aggregate" taggedAs DatabaseTest in {

      val userId = UUID.randomUUID()
      val nonExistentRecord = hydrate(userId) update
        updateEmail("new@email.com")

      runSync(nonExistentRecord.state()) shouldBe Symbol("left")
      runSync(nonExistentRecord.persist()) shouldBe Symbol("left")
    }

    "report provided error message" taggedAs DatabaseTest in {
      val errorMessage = "test error"

      val unconditionalErr: SourcedUpdate[UserRecord, Nothing, Nothing] =
        EventSourced.sourceError(errorMessage)

      val sourceError =
        createNew(createUser(UUID.randomUUID(), "test@test.com")) update
          updateEmail("new@email.com") update unconditionalErr

      val errOut = new StringWriter()
      val s = runSync(sourceError.state())
      s shouldBe Symbol("left")
      val actualMessage: String = s.left.map { err =>
        err.printStackTrace(new PrintWriter(errOut))
        errOut.flush()
        errOut.toString
      }.left.getOrElse("")
      actualMessage should include( errorMessage)
    }

    "not trigger snapshot if snapshot interval not exceeded" taggedAs DatabaseTest in {
      forAll { initialState: UserRecord =>
        val initial: SourcedCreation[UserRecord, UserRecordChangeEvent, UUID] =
          createUser(initialState.id, initialState.email)
        val allOps = (1 until (snapshotIntervalValue - 1)).foldLeft(
          createNew(initial).map(_ => ())
        ) { (sc, n) =>
          val scUp = sc.update(updateEmail(s"updated_$n@test.com").map(_ => ()))
          scUp
        }
        runSync(allOps.persist())

        val snapshot = runSync(
          recordEventStore
            .loadLatestStateSnapshot(initialState.id)
        ).fold(throw _, identity)
        snapshot shouldNot be(Symbol("defined"))

        val events = loadEvents(initialState.id)
        events should not be empty
      }
    }

    "trigger snapshot if snapshot interval exceeded" taggedAs DatabaseTest in {
      forAll { initialState: UserRecord =>
        val initial = createUser(initialState.id, initialState.email)

        val allOps = (1 until snapshotIntervalValue)
          .foldLeft(SourcedUpdate.pure[UserRecord, UserRecordChangeEvent](())) {
            (sc, n) =>
              sc andThen updateEmail(s"updated_$n@test.com").map(_ => ())
          }
        // this would persist event and creates a snapshot
        runSync(createNew(initial).update(allOps).persist())

        val finalEmail = "final@test.com"
        // this would simply append a single event
        val latestState = runSync(
          hydrate(initialState.id)
            .update(updateEmail(finalEmail))
            .persist()
        ).map(_._2).toOption
        latestState.map(_.email) should be(Some(finalEmail))

        val snapshot = runSync(
          recordEventStore.loadLatestStateSnapshot(initialState.id)
        ).toOption.flatten
        snapshot should be(Symbol("defined"))
        snapshot.map(_._1) should not be equal(latestState)

        val hydratedState = runSync(hydrate(initialState.id).state()).toOption
        hydratedState shouldEqual latestState
      }
    }
  }

  override protected final def stateSnapshotInterval: Option[Int] = Some(snapshotIntervalValue)

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