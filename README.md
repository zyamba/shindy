# shindy
[![CircleCI](https://circleci.com/gh/zyamba/shindy.svg?style=shield&circle-token=64e321432a5ce4e96a85cb9e02af5605155131af)](https://circleci.com/gh/zyamba/shindy)
[![codecov](https://codecov.io/gh/zyamba/shindy/branch/master/graph/badge.svg)](https://codecov.io/gh/zyamba/shindy)
[![Maintainability](https://api.codeclimate.com/v1/badges/1b81990fd51dbe537474/maintainability)](https://codeclimate.com/github/zyamba/shindy/maintainability)



Lightweight Composible Event Sourcing library for Scala.

Heavily inspired by [scalaio-2017-esmonad](https://github.com/dohzya/scalaio-2017-esmonad) presentation.

## Usage Example

```scala

object UserService {

  // state
  case class Address(country: String, zip: String, strLine1: String, strLine2: Option[String] = None,
    state: Option[String] = None)
  sealed case class UserRecord(id: UUID, email: String, birthdate: Option[LocalDate] = None,
    addresses: Vector[Address] = Vector.empty)

  // events
  sealed trait UserRecordChangeEvent extends Product with Serializable
  case class UserCreated(id: UUID, email: String) extends UserRecordChangeEvent
  case class EmailUpdated(newEmail: String) extends UserRecordChangeEvent
  case class BirthdateUpdated(birthdate: LocalDate) extends UserRecordChangeEvent
  case class AddressAdded(newAddress: Address) extends UserRecordChangeEvent

  // state machine
  implicit val eventHandler: EventHandler[UserRecord, UserRecordChangeEvent] = EventHandler {
    case (None, ev: UserCreated) => UserRecord(ev.id, ev.email)

    case (Some(s: UserRecord), ev: EmailUpdated) => s.copy(email = ev.newEmail)

    case (Some(u: UserRecord), BirthdateUpdated(newDate)) => u.copy(birthdate = Some(newDate))

    case (Some(u: UserRecord), AddressAdded(a)) => u.copy(addresses = u.addresses :+ a)
  }

  // business logic
  def createUser(id: UUID, email: String): SourcedCreation[UserRecord, UserCreated, UUID] =
    sourceNew[UserRecord](UserCreated(id, email).asRight).map(_ => id)

  def updateEmail(email: String): SourcedUpdate[UserRecord, EmailUpdated, Unit] = source {
    _: UserRecord => Either.cond(email.contains("@"), EmailUpdated(email), "email is invalid")
  }

  def changeBirthdate(birthdate: LocalDate): SourcedUpdate[UserRecord, BirthdateUpdated, Unit] = source {
    _: UserRecord =>
      Either.cond(
        birthdate.isBefore(LocalDate.of(2018, 1, 1)),
        BirthdateUpdated(birthdate),
        "Too young!"
      )
  }

  def addAddress(country: String, zip: String, strLine1: String, strLine2: Option[String] = None,
    state: Option[String] = None): SourcedUpdate[UserRecord, AddressAdded, Unit] = sourceOut { _ =>
      Either.cond(country.nonEmpty && strLine1.nonEmpty && zip.nonEmpty,
        AddressAdded(Address(country, zip, strLine1, strLine2, state)) -> "",
        "Invalid address"
      )
    }

  // composing multiple actions into single action
  def createUser(email: String, birthDate: LocalDate): SourcedCreation[UserRecord, UserRecordChangeEvent, UUID] = {
    // Side effect that produces id is outside of the `source` scope. Thus it remains pure.
    // In other words "id" value remain unchanged if source executed more then once (in case of a retry for example).
    val id = UUID.randomUUID()
    createUser(id, email) andThen { id =>
        changeBirthdate(birthDate).map(_ => id)
      }
    }

  def main(args: Array[String]): Unit = {
    // example of execution
    val smallProgram = createUser("test@email.com", LocalDate.of(1970, 1, 1)) andThen {
      addAddress("United States", "10001", "1 Main str", state = Some("NY"))
    }

    /**
     * Prints out:
     *
     * 0: UserCreated(c6e105bb-0227-4c0c-b106-a0be5ae0f204,test@email.com)
     * 1: BirthdateUpdated(1970-01-01)
     * 2: AddressAdded(Address(United States,10001,1 Main str,None,Some(NY)))
     *
     * UserRecord(c6e105bb-0227-4c0c-b106-a0be5ae0f204,test@email.com,Some(1970-01-01),
     *   Vector(Address(United States,10001,1 Main str,None,Some(NY))))
     */
    smallProgram.run.map { case (events, finalState, out) =>
      println(events.zipWithIndex.map(l => s"${l._2}: ${l._1}").mkString("\n"))
      println("\n")
      println(finalState)
    }
  }
}
```
