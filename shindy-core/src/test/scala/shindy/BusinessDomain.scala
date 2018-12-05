package shindy

import java.time.LocalDate
import java.util.UUID

import cats.syntax.either._

import shindy.EventSourced.{EventHandler, source, sourceNew}

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
