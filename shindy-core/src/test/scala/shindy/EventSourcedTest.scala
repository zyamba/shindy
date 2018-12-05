package shindy

import java.time.LocalDate
import java.util.UUID

import cats.syntax.either._
import cats.syntax.option._
import org.scalatest.{FreeSpec, Matchers}

import scala.language.{postfixOps, reflectiveCalls}

class EventSourcedTest extends FreeSpec with Matchers {

  import EventSourced._




  "Basic functionality" - {
    import BusinessDomain._

    "should be able to capture creation event" in {
      val email = "test@yahoo.com"
      val userId = UUID.randomUUID()
      val results = createUser(userId, email) run

      results should be('right)
      val (events, state, _) = results.right.get

      events should have size 1
      events.head shouldEqual UserCreated(userId, email)

      state shouldEqual UserRecord(userId, email)
    }

    "should be able to execute update of the given state" in {
      val updEmail = "new@yahoo.com"
      val results = updateEmail(updEmail) run UserRecord(UUID.randomUUID(), "original@google.com", None)
      results should be('right)

      val (events, state, _) = results.right.get
      events should have size 1
      events.head shouldEqual EmailUpdated(updEmail)

      state.email shouldEqual updEmail
    }

    "should report domain errors" in {
      val email = "test@yahoo.com"
      val userId = UUID.randomUUID()
      val results = createUser(userId, email) andThen changeBirthdate(LocalDate.of(2018,12,12)) run

      results should be('left)
      results.left.get should include ("Too young")
    }

    "should be able to execute conditional update" in {
      val happyBirthdayEmail = "happy@birthday.com"
      val happyBirthdayMsg = "Happy Birthday"
      val conditionalUpdate =
        when(
          (user: UserRecord) => user.birthdate.isDefined,
          updateEmail(happyBirthdayEmail).map(_ => happyBirthdayMsg)
        )

      val stateFalse = UserRecord(UUID.randomUUID(), "test@test.com")

      {
        val results = conditionalUpdate run stateFalse
        results should be('right)
        val (events, state, out) = results.right.get
        events should be(empty)
        state shouldEqual stateFalse
        out shouldBe None
      }

      val stateTrue = stateFalse.copy(birthdate = Some(LocalDate.of(2000, 1, 1)))

      {
        val results = conditionalUpdate run stateTrue
        results should be('right)
        val (events, state, out) = results.right.get
        events should have size 1
        events.head shouldEqual EmailUpdated(happyBirthdayEmail)
        state.email shouldEqual happyBirthdayEmail
        out shouldBe Some(happyBirthdayMsg)
      }

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
      results should be('right)
      val (events, finalState, _) = results.right.get

      events should contain inOrder(
        UserCreated(userId, regEmail), EmailUpdated(updEmail), BirthdateUpdated(birthdate)
      )
      finalState shouldEqual UserRecord(userId, updEmail, birthdate.some)
    }

    "should be able to compose update operations using 'for comprehension'" in {
      val userId = UUID.randomUUID()
      val regEmail = "test@google.com"
      val updEmail = "test@yahoo.com"
      val birthdate = LocalDate.of(2000, 1, 1)

      val modifyUser =
        for {
          s1 <- updateEmail(updEmail).map(_ => "Hello, ").adaptEvent[UserRecordChangeEvent]
          s2 <- changeBirthdate(birthdate).map(_ => "world")
        } yield s1 + s2

      val results = (createUser(userId, regEmail) andThen modifyUser) run

      results should be('right)
      val (events, finalState, msg) = results.right.get

      events should contain inOrder(
        UserCreated(userId, regEmail), EmailUpdated(updEmail), BirthdateUpdated(birthdate)
      )
      finalState shouldEqual UserRecord(userId, updEmail, birthdate.some)
      msg shouldEqual "Hello, world"
    }

  }
}
