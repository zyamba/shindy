package shindy

import java.time.LocalDate
import java.util.UUID

import cats.effect.IO
import cats.syntax.either._
import org.scalatest.{FreeSpec, Matchers}
import shindy.EventSourced.{EventHandler, source, sourceNew}

import scala.collection.mutable

class HydratableTest extends FreeSpec with Matchers {

  object BusinessDomain {

    // state
    sealed case class UserRecord(id: UUID, email: String, birthdate: Option[LocalDate] = None)

    // events
    sealed trait UserRecordChangeEvent extends Product with Serializable
    case class UserCreated(id: UUID, email: String) extends UserRecordChangeEvent
    case class EmailUpdated(newEmail: String) extends UserRecordChangeEvent
    case class BirthdateUpdated(birthdate: LocalDate) extends UserRecordChangeEvent

    // state machine
    implicit val eventHandler: EventHandler[UserRecord, UserRecordChangeEvent] = EventHandler {
      case (None, ev: UserCreated) => UserRecord(ev.id, ev.email)

      case (Some(s: UserRecord), ev: EmailUpdated) => s.copy(email = ev.newEmail)

      case (Some(u: UserRecord), BirthdateUpdated(newDate)) => u.copy(birthdate = Some(newDate))
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
  }

  import BusinessDomain._

  class InMemoryEventDatabase extends EventStore[UserRecordChangeEvent, IO] {
    private val store = mutable.Map.empty[UUID, Vector[UserRecordChangeEvent]]

    override def loadEvents(aggregateId: UUID): fs2.Stream[IO, BusinessDomain.UserRecordChangeEvent] =
      fs2.Stream.fromIterator[IO, UserRecordChangeEvent](store(aggregateId).iterator)

    override def storeEvents(aggregateId: UUID, events: Vector[BusinessDomain.UserRecordChangeEvent]): IO[Unit] = {
      IO {
        val storedEvents = store.getOrElseUpdate(aggregateId, Vector.empty)
        store.update(aggregateId, storedEvents ++ events)
      }
    }
  }

  "Methods tests" - {
    val eventStore = new InMemoryEventDatabase()
    "hydrate" in {

    }

    "createNew" in {

      val userId = UUID.randomUUID()
      val p = Hydratable.createNew[UserRecord, UserRecordChangeEvent, IO](
        createUser(userId, "test@test.com")
      ) applyAndSaveChanges updateEmail("updated@email.com")

      val value = p.run(eventStore).unsafeRunSync()
      value should be ('right)

      val (id, state, _) = value.right.get
      id shouldBe userId
      state.email shouldBe "updated@email.com"

      val results = eventStore.loadEvents(id).compile.toList unsafeRunSync()
      println(results)
    }

  }
}
