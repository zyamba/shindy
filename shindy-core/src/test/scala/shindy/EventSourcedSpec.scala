package shindy

import cats.syntax.option.*
import org.scalatest.Inside
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import shindy.EventSourced.sourceNew

import java.time.LocalDate
import java.util.UUID
import scala.language.{postfixOps, reflectiveCalls}

class EventSourcedSpec extends AnyFreeSpec with Matchers with Inside:

  import EventSourced.*

  "Basic functionality" - {
    import UserRecordService.*

    "should be able to capture creation event" in {
      val email = "test@yahoo.com"
      val userId = UUID.randomUUID()
      val results = UserAggregate.create(userId, email) run

      results.isRight shouldBe true
      inside(results) { case Right((events, state, _)) =>
        events should have size 1
        events.head shouldEqual UserCreated(userId, email)

        state shouldEqual UserRecordActive(userId, email)
      }
    }

    "should be able to get latest state by calling 'get'" in {
      val email = "test@yahoo.com"
      val userId = UUID.randomUUID()
      val result = UserAggregate.create(userId, email).get.run
      result.isRight shouldBe true
      inside(result) { case Right((_, state, stateOut)) =>
        state shouldEqual stateOut
        state shouldEqual UserRecordActive(userId, email)
      }
    }

    "should be able to execute update of the given state" in {
      val updEmail = "new@yahoo.com"
      val results =
        UserAggregate.updateEmail(updEmail) run UserRecordActive(UUID.randomUUID(), "original@google.com", None)
      results.isRight shouldBe true

      inside(results) { case Right((events, state, _)) =>
        events should have size 1
        events.head shouldEqual EmailUpdated(updEmail)

        state.asInstanceOf[UserRecordActive].email shouldEqual updEmail
      }
    }

    "should report domain errors" in {
      val email = "test@yahoo.com"
      val userId = UUID.randomUUID()
      val results =
        UserAggregate.create(userId, email) andThen UserAggregate.changeBirthdate(LocalDate.of(2018, 12, 12)) run

      results.isLeft shouldBe true
      results.left.getOrElse("") should include("Too young")
    }

    "should be able to execute conditional update" in {
      val happyBirthdayEmail = "happy@birthday.com"
      val happyBirthdayMsg = "Happy Birthday"
      val conditionalUpdate =
        when(
          (user: UserRecordActive) => user.birthdate.isDefined,
          UserAggregate.updateEmail(happyBirthdayEmail).map(_ => happyBirthdayMsg)
        )

      val stateDoesNotMatchCond = UserRecordActive(UUID.randomUUID(), "test@test.com")

      {
        val results = conditionalUpdate run stateDoesNotMatchCond
        results.isRight shouldBe true
        inside(results) { case Right((events, state, out)) =>
          events should be(empty)
          state shouldEqual stateDoesNotMatchCond
          out shouldBe None
        }
      }

      val stateMatchesCond = stateDoesNotMatchCond.copy(birthdate = Some(LocalDate.of(2000, 1, 1)))

      {
        val result = conditionalUpdate run stateMatchesCond
        result.isRight shouldBe true
        inside(result) { case Right((events, state, out)) =>
          events should have size 1
          events.head shouldEqual EmailUpdated(happyBirthdayEmail)
          state.asInstanceOf[UserRecordActive].email shouldEqual happyBirthdayEmail
          out shouldBe Some(happyBirthdayMsg)
        }
      }

    }

    "should execute conditional update when state is of expected type" in {
      val activeUser = UserRecordActive(UUID.randomUUID(), "test@test.com")

      val output = "Success"
      val updatedEmail = "updated@test.com"
      val condOp = whenStateIs((_: UserRecordActive) => UserAggregate.updateEmail(updatedEmail).map(_ => output))

      val runTrue = condOp.run(activeUser)
      runTrue.isRight shouldBe true

      inside(runTrue) { case Right((ev, u, out)) =>
        ev should have size 1
        u.asInstanceOf[UserRecordActive].email shouldEqual updatedEmail
        out shouldEqual Some(output)
      }

    }

    "should not execute conditional update when state is not of expected type" in {
      val inactiveUser = UserRecordInactive(UserRecordActive(UUID.randomUUID(), "test@test.com"))

      val updatedEmail = "updated@test.com"
      val condOp =
        whenStateIs((_: UserRecordActive) => UserAggregate.updateEmail(updatedEmail).map(_ => "should not happen"))

      val runFalse = condOp.run(inactiveUser)
      runFalse.isRight shouldBe true
      inside(runFalse) { case Right((ev, u, out)) =>
        ev.isEmpty shouldBe true
        u.asInstanceOf[UserRecordInactive].suspendedState.email should not equal updatedEmail
        out shouldEqual None
      }
    }

    "should be able to compose operations using 'andThen'" in {

      val userId = UUID.randomUUID()
      val regEmail = "test@google.com"
      val updEmail = "test@yahoo.com"
      val birthdate = LocalDate.of(2000, 1, 1)

      val createAndModifyUser =
        UserAggregate.create(userId, regEmail) andThen { _ =>
          UserAggregate.updateEmail(updEmail)
        } andThen { _ =>
          UserAggregate.changeBirthdate(birthdate)
        }

      val results = createAndModifyUser.run
      results.isRight shouldBe true
      inside(results) { case Right((events, finalState, _)) =>
        events should contain inOrder (
          UserCreated(userId, regEmail),
          EmailUpdated(updEmail),
          BirthdateUpdated(birthdate)
        )
        finalState shouldEqual UserRecordActive(userId, updEmail, birthdate.some)
      }
    }

    "should be able to compose update operations using 'for comprehension'" in {
      val userId = UUID.randomUUID()
      val regEmail = "test@google.com"
      val updEmail = "test@yahoo.com"
      val birthdate = LocalDate.of(2000, 1, 1)

      val modifyUser =
        for
          s1 <- UserAggregate.updateEmail(updEmail).map(_ => "Hello, ")
          s2 <- UserAggregate.changeBirthdate(birthdate).map(_ => "world")
        yield s1 + s2

      val results = (UserAggregate.create(userId, regEmail) andThen modifyUser) run

      results.isRight shouldBe true
      inside(results) { case Right((events, finalState, msg)) =>
        events should contain inOrder (
          UserCreated(userId, regEmail),
          EmailUpdated(updEmail),
          BirthdateUpdated(birthdate)
        )
        finalState shouldEqual UserRecordActive(userId, updEmail, birthdate.some)
        msg shouldEqual "Hello, world"
      }
    }

    "should fail if the sourceNew block fails" in {
      val errMessage = "Error creating UserRecord"
      val errSourced = sourceNew[UserRecord](Left(errMessage)) andThen UserAggregate.updateEmail("wrong-email")

      val runResult = errSourced.run
      runResult.isLeft shouldBe true
      runResult.left.getOrElse("") should include(errMessage)
    }

    "should fail if error is sourced" in {
      val errMessage = "Error sourced"
      val errSourced = whenStateIs { (_: UserRecordActive) =>
        sourceError(errMessage)
      }

      val userRecordState = UserRecordActive(UUID.randomUUID(), "test@test.com")
      val runResult = errSourced.run(userRecordState)
      runResult.isLeft shouldBe true
      runResult.left.getOrElse("") should include(errMessage)
    }

    "should be able to inspect state" in {
      val inspectEmail =
        SourcedEval.pure(()).inspect {
          case e: UserRecordActive => Some(e.email)
          case _                   => None
        }

      val userRecordState = UserRecordActive(UUID.randomUUID(), "test@test.com")
      val runResult = inspectEmail.run(userRecordState)
      runResult.isRight shouldBe true
      inside(runResult) { case Right((_, _, maybeState)) =>
        maybeState shouldEqual Option(userRecordState.email)
      }
    }

    "should be able to collect events from SourcedCreate and SourcedUpdate" in {
      val sourcedCreate = UserAggregate.create(UUID.randomUUID(), "test1@test.com")
      val sourcedUpdate = UserAggregate
        .updateEmail("test2@test.com")
        .andThen(UserAggregate.updateEmail("test3@test.com"))
        .andThen(UserAggregate.changeBirthdate(LocalDate.of(2000, 1, 2)))
      val program = sourcedCreate andThen sourcedUpdate

      val eventsEither = program.events(null)
      eventsEither.isRight shouldBe true
      inside(eventsEither) { case Right(events) =>
        events should have size
      }

      val updateEventsEither = sourcedUpdate.events(
        UserRecordActive(UUID.randomUUID(), "one@test.com")
      )
      updateEventsEither.isRight shouldBe true
      inside(updateEventsEither) { case Right(updateEvents) =>
        updateEvents should have size 3
      }
    }
  }

  "EventHandler" - {
    "should throw RuntimeException if there is no handler for the event" in {
      import UserRecordService.*

      val userRecordState = UserRecordActive(UUID.randomUUID(), "test@test.com")
      val exception = the[RuntimeException] thrownBy UserAggregate.suspend.run(userRecordState)

      exception.getMessage should (
        include("Unhandled event")
          and include("Suspended")
          and include(userRecordState.getClass.getSimpleName)
      )
    }
  }
