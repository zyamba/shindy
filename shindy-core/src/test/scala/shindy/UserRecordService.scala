package shindy

import java.time.LocalDate
import java.util.UUID

import cats.implicits._
import shindy.EventSourced.{EventHandler, source, sourceNew}

object UserRecordService {

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
