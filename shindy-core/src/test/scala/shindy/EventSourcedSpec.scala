package shindy

import java.time.LocalDate
import java.util.UUID

import cats.syntax.either._
import cats.syntax.option._
import org.scalatest.{FreeSpec, Matchers}
import shindy.EventSourced.{EventHandler, source, sourceNew}

import scala.language.{postfixOps, reflectiveCalls}

private object BusinessDomain {

  // state
  sealed trait UserRecord

  sealed case class UserRecordActive(id: UUID, email: String, birthdate: Option[LocalDate] = None) extends UserRecord

  sealed case class UserRecordInactive(suspended: UserRecordActive) extends UserRecord

  // events
  sealed trait UserRecordChangeEvent extends Product with Serializable

  case class UserCreated(id: UUID, email: String) extends UserRecordChangeEvent

  case class EmailUpdated(newEmail: String) extends UserRecordChangeEvent

  case class BirthdateUpdated(birthdate: LocalDate) extends UserRecordChangeEvent

  case class Suspended() extends UserRecordChangeEvent

  // state machine
  implicit val eventHandler: EventHandler[UserRecord, UserRecordChangeEvent] = EventHandler {
    case (None, ev: UserCreated) => UserRecordActive(ev.id, ev.email)

    case (Some(s: UserRecordActive), ev: EmailUpdated) => s.copy(email = ev.newEmail)

    case (Some(u: UserRecordActive), BirthdateUpdated(newDate)) => u.copy(birthdate = Some(newDate))
  }

  // business logic
  def createUser(id: UUID, email: String): SourcedCreation[UserRecord, UserCreated, UUID] =
    sourceNew[UserRecord](UserCreated(id, email).asRight).map(_ => id)

  def updateEmail(email: String): SourcedUpdate[UserRecord, EmailUpdated, Unit] = source {
    _: UserRecord => Either.cond(email.contains("@"), EmailUpdated(email), "email is invalid")
  }

  def changeBirthdate(datetime: LocalDate): SourcedUpdate[UserRecord, BirthdateUpdated, Unit] = source {
    _: UserRecord =>
      Either.cond(
        datetime.isBefore(LocalDate.of(2018, 1, 1)),
        BirthdateUpdated(datetime),
        "Too young!"
      )
  }

  def suspend(): SourcedUpdate[UserRecord, Suspended, Unit] = source(_ => Suspended().asRight)
}

class EventSourcedSpec extends FreeSpec with Matchers {

  import EventSourced._


  "Basic functionality" - {
    import BusinessDomain._

    "should be able to capture creation event" in {
      val email = "test@yahoo.com"
      val userId = UUID.randomUUID()
      val results = createUser(userId, email) run

      results should be(Symbol("right"))
      val Right((events, state, _)) = results

      events should have size 1
      events.head shouldEqual UserCreated(userId, email)

      state shouldEqual UserRecordActive(userId, email)
    }

    "should be able to execute update of the given state" in {
      val updEmail = "new@yahoo.com"
      val results = updateEmail(updEmail) run UserRecordActive(UUID.randomUUID(), "original@google.com", None)
      results should be(Symbol("right"))

      val Right((events, state, _)) = results
      events should have size 1
      events.head shouldEqual EmailUpdated(updEmail)

      state.asInstanceOf[UserRecordActive].email shouldEqual updEmail
    }

    "should report domain errors" in {
      val email = "test@yahoo.com"
      val userId = UUID.randomUUID()
      val results = createUser(userId, email) andThen changeBirthdate(LocalDate.of(2018, 12, 12)) run

      results should be(Symbol("left"))
      results.left.getOrElse("") should include("Too young")
    }

    "should be able to execute conditional update" in {
      val happyBirthdayEmail = "happy@birthday.com"
      val happyBirthdayMsg = "Happy Birthday"
      val conditionalUpdate =
        when(
          (user: UserRecordActive) => user.birthdate.isDefined,
          updateEmail(happyBirthdayEmail).map(_ => happyBirthdayMsg)
        )

      val stateFalse = UserRecordActive(UUID.randomUUID(), "test@test.com")

      {
        val results = conditionalUpdate run stateFalse
        results should be(Symbol("right"))
        val Right((events, state, out)) = results
        events should be(empty)
        state shouldEqual stateFalse
        out shouldBe None
      }

      val stateTrue = stateFalse.copy(birthdate = Some(LocalDate.of(2000, 1, 1)))

      {
        val results = conditionalUpdate run stateTrue
        results should be(Symbol("right"))
        val Right((events, state, out)) = results
        events should have size 1
        events.head shouldEqual EmailUpdated(happyBirthdayEmail)
        state.asInstanceOf[UserRecordActive].email shouldEqual happyBirthdayEmail
        out shouldBe Some(happyBirthdayMsg)
      }

    }

    "should execute conditional update when state is of expected type" in {
      val activeUser = UserRecordActive(UUID.randomUUID(), "test@test.com")

      val output = "Success"
      val updatedEmail = "updated@test.com"
      val condOp = whenStateIs((_: UserRecordActive) => {
        updateEmail(updatedEmail).map(_ => output)
      })

      val runTrue = condOp.run(activeUser)
      runTrue should be(Symbol("right"))

      val Right((ev, u, out)) = runTrue
      ev should have size 1
      u.asInstanceOf[UserRecordActive].email shouldEqual updatedEmail
      out shouldEqual Some(output)

    }

    "should not execute conditional update when state is not of expected type" in {
      val inactiveUser = UserRecordInactive(UserRecordActive(UUID.randomUUID(), "test@test.com"))

      val updatedEmail = "updated@test.com"
      val condOp = whenStateIs(
        (_: UserRecordActive) => {
          updateEmail(updatedEmail).map(_ => "should not happen")
        })

      val runFalse = condOp.run(inactiveUser)
      runFalse should be(Symbol("right"))

      val Right((ev, u, out)) = runFalse
      ev should be(Symbol("empty"))
      u.asInstanceOf[UserRecordInactive].suspended.email should not equal updatedEmail
      out shouldEqual None
    }

    "should be able to compose operations using 'andThen'" in {

      val userId = UUID.randomUUID()
      val regEmail = "test@google.com"
      val updEmail = "test@yahoo.com"
      val birthdate = LocalDate.of(2000, 1, 1)

      val createAndModifyUser =
        createUser(userId, regEmail) andThen {
          _ => updateEmail(updEmail)
        } andThen {
          _ => changeBirthdate(birthdate)
        }

      val results = createAndModifyUser.run
      results should be(Symbol("right"))
      val Right((events, finalState, _)) = results

      events should contain inOrder(
        UserCreated(userId, regEmail), EmailUpdated(updEmail), BirthdateUpdated(birthdate)
      )
      finalState shouldEqual UserRecordActive(userId, updEmail, birthdate.some)
    }

    "should be able to compose update operations using 'for comprehension'" in {
      val userId = UUID.randomUUID()
      val regEmail = "test@google.com"
      val updEmail = "test@yahoo.com"
      val birthdate = LocalDate.of(2000, 1, 1)

      val modifyUser =
        for {
          s1 <- updateEmail(updEmail).map(_ => "Hello, ").widen[UserRecordChangeEvent]
          s2 <- changeBirthdate(birthdate).map(_ => "world")
        } yield s1 + s2

      val results = (createUser(userId, regEmail) andThen modifyUser) run

      results should be(Symbol("right"))
      val Right((events, finalState, msg)) = results

      events should contain inOrder(
        UserCreated(userId, regEmail), EmailUpdated(updEmail), BirthdateUpdated(birthdate)
      )
      finalState shouldEqual UserRecordActive(userId, updEmail, birthdate.some)
      msg shouldEqual "Hello, world"
    }

    "should fail if the sourceNew block fails" in {
      val errMessage = "Error creating UserRecord"
      val errSourced = sourceNew[UserRecord](Left(errMessage)) andThen updateEmail("wrong-email")

      val runResult = errSourced.run
      runResult should be(Symbol("left"))
      runResult.left.getOrElse("") should include(errMessage)
    }

    "should fail if error is sourced" in {
      val errMessage = "Error sourced"
      val errSourced: SourcedUpdate[UserRecord, UserRecordChangeEvent, Option[Unit]] = whenStateIs {
        _: UserRecordActive => sourceError(errMessage)
      }

      val userRecordState = UserRecordActive(UUID.randomUUID(), "test@test.com")
      val runResult = errSourced.run(userRecordState)
      runResult should be(Symbol("left"))
      runResult.left.getOrElse("") should include(errMessage)
    }

    "should be able to inspect state" in {
      val inspectEmail: SourcedUpdate[UserRecord, UserRecordChangeEvent, Option[String]] =
        SourcedUpdate.pure(()).inspect {
          case e: UserRecordActive => Some(e.email)
          case _ => None
        }

      val userRecordState = UserRecordActive(UUID.randomUUID(), "test@test.com")
      val runResult = inspectEmail.run(userRecordState)
      runResult should be(Symbol("right"))
      runResult.getOrElse(null)._3 shouldEqual Option(userRecordState.email)
    }

    "should be able to collect events from SourcedCreate and SourcedUpdate" in {
      val sourcedCreate = createUser(UUID.randomUUID(), "test1@test.com")
      val sourcedUpdate = updateEmail("test2@test.com").
        andThen(updateEmail("test3@test.com")).
        andThen(changeBirthdate(LocalDate.of(2000, 1, 2)))
      val program = sourcedCreate andThen sourcedUpdate

      val eventsEither = program.events
      eventsEither should be(Symbol("right"))
      val Right(events) = eventsEither
      events should have size 4

      val updateEventsEither = sourcedUpdate.events(
        UserRecordActive(UUID.randomUUID(), "one@test.com")
      )
      updateEventsEither should be (Symbol("right"))
      val Right(updateEvents) = updateEventsEither
      updateEvents should have size 3

    }
  }

  "EventHandler" - {
    "should throw RuntimeException if there is no handler for the event" in {
      import BusinessDomain._

      val userRecordState = UserRecordActive(UUID.randomUUID(), "test@test.com")
      val exception = the[RuntimeException] thrownBy suspend().run(userRecordState)


      exception.getMessage should (
        include("Unhandled event")
          and include("Suspended")
          and include(userRecordState.getClass.getSimpleName)
        )
    }
  }
}
