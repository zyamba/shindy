package shindy

import java.time.LocalDate
import java.util.UUID

import cats.implicits.*
import shindy.EventSourced.{EventHandler, source, sourceNew}

object UserRecordService:

  // state
  sealed trait UserRecord

  sealed case class UserRecordActive(id: UUID, email: String, birthdate: Option[LocalDate] = None) extends UserRecord

  sealed case class UserRecordInactive(suspendedState: UserRecordActive) extends UserRecord

  // events
  sealed trait UserRecordChangeEvent extends Product with Serializable

  case class UserCreated(id: UUID, email: String) extends UserRecordChangeEvent

  case class EmailUpdated(newEmail: String) extends UserRecordChangeEvent

  case class BirthdateUpdated(birthdate: LocalDate) extends UserRecordChangeEvent

  case class Suspended() extends UserRecordChangeEvent

  // state machine
  given eventHandler: EventHandler[UserRecord, UserRecordChangeEvent] = EventHandler {
    case (null, ev: UserCreated) => UserRecordActive(ev.id, ev.email)

    case (s: UserRecordActive, ev: EmailUpdated) => s.copy(email = ev.newEmail)

    case (u: UserRecordActive, BirthdateUpdated(newDate)) => u.copy(birthdate = Some(newDate))
  }

  object UserAggregate extends EventSourced[UserRecord, UserRecordChangeEvent]:
    // business logic
    def create(id: UUID, email: String) =
      sourceNew(UserCreated(id, email).asRight).map(_ => id)

    def updateEmail(email: String) = source { (_: UserRecord) =>
      Either.cond(email.contains("@"), EmailUpdated(email), "email is invalid")
    }

    def changeBirthdate(datetime: LocalDate) = source { (_: UserRecord) =>
      Either.cond(
        datetime.isBefore(LocalDate.of(2018, 1, 1)),
        BirthdateUpdated(datetime),
        "Too young!"
      )
    }

    def suspend = source(_ => Suspended().asRight)
