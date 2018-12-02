# shindy

Lightweight Event Sourcing library for scala.

Heavily inspired by [scalaio-2017-esmonad](https://github.com/dohzya/scalaio-2017-esmonad) presentation.

## Usage Example

```scala
  import shindy._
  import shindy.EventSourced._
  import java.time.LocalDate
  import java.util.UUID
  import cats.syntax.either._

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
    def createUser(id: UUID, email: String): SourcedCreation[UserRecord, UserCreated, Unit] =
      sourceNew[UserRecord](UserCreated(id, email).asRight)

    def updateEmail(email: String): SourcedUpdate[UserRecord, EmailUpdated, Unit] = source {
      _: UserRecord => Either.cond(email.contains("@"), EmailUpdated(email), "email is invalid")
    }

    def changeBirthdate(datetime: LocalDate): SourcedUpdate[UserRecord, BirthdateUpdated, Unit] = source {
      user: UserRecord =>
        Either.cond(
          datetime.isBefore(LocalDate.of(2018, 1, 1)),
          BirthdateUpdated(datetime),
          "Too young!"
        )
    }
  }
  
  import BusinessDomain._
  
  val results = createUser(UUID.randomUUID(), "email@test.com") andThen changeBirthdate(LocalDate.of(1970, 1, 1)) run
  val (events, finalState, _) = results.right.get
  
  println(events)
  println(finalState)
```