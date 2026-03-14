# shindy
[![CircleCI](https://circleci.com/gh/zyamba/shindy.svg?style=shield&circle-token=64e321432a5ce4e96a85cb9e02af5605155131af)](https://circleci.com/gh/zyamba/shindy)
[![codecov](https://codecov.io/gh/zyamba/shindy/branch/master/graph/badge.svg)](https://codecov.io/gh/zyamba/shindy)
[![Maintainability](https://api.codeclimate.com/v1/badges/1b81990fd51dbe537474/maintainability)](https://codeclimate.com/github/zyamba/shindy/maintainability)


Lightweight Composible Event Sourcing library for Scala.

Heavily inspired by [scalaio-2017-esmonad](https://github.com/dohzya/scalaio-2017-esmonad) presentation.

## Usage Example without using EventStore

### Define state

```scala
case class Address(
  country: String,
  zip: String,
  strLine1: String,
  strLine2: Option[String] = None,
  state: Option[String] = None
)
sealed case class UserRecord(
  id: UUID,
  email: String,
  birthdate: Option[LocalDate] = None,
  addresses: Vector[Address] = Vector.empty
)
```

### Define events
```scala 3
  sealed trait UserRecordChangeEvent
  case class UserCreated(id: UUID, email: String) extends UserRecordChangeEvent
  case class EmailUpdated(newEmail: String) extends UserRecordChangeEvent
  case class BirthdateUpdated(birthdate: LocalDate) extends UserRecordChangeEvent
  case class AddressAdded(newAddress: Address) extends UserRecordChangeEvent

```
### Define state machine
State machine changes each state according to the event

```scala 3
  given EventHandler[UserRecord, UserRecordChangeEvent] = EventHandler {
    case (None, ev: UserCreated) => UserRecord(ev.id, ev.email)

    case (Some(s: UserRecord), ev: EmailUpdated) => s.copy(email = ev.newEmail)

    case (Some(u: UserRecord), BirthdateUpdated(newDate)) => u.copy(birthdate = Some(newDate))

    case (Some(u: UserRecord), AddressAdded(a)) => u.copy(addresses = u.addresses :+ a)
  }
```

### Define the aggregate object and its operations
```scala 3
object UserAggregate extends EventSourced[UserRecord, UserRecordChangeEvent]:
    def createUser(id: UUID, email: String) = sourceNew(UserCreated(id, email).asRight).map(_ => id)

    def updateEmail(email: String) = source: _ =>
      Either.cond(email.contains("@"), EmailUpdated(email), "email is invalid")

    def changeBirthdate(birthdate: LocalDate) = source { _ =>
      Either.cond(
        birthdate.isBefore(LocalDate.of(2018, 1, 1)),
        BirthdateUpdated(birthdate),
        "Too young!"
      )
    }

    def addAddress(
        country: String,
        zip: String,
        strLine1: String,
        strLine2: Option[String] = None,
        state: Option[String] = None
    ) =
      source { _ =>
        Either.cond(
          country.nonEmpty && strLine1.nonEmpty && zip.nonEmpty,
          AddressAdded(Address(country, zip, strLine1, strLine2, state)),
          "Invalid address"
        )
      }

    // composing multiple actions into a single method
    def createCompleteUser(
        email: String,
        birthDate: LocalDate
    ): SourcedEval[Unit, UserRecord, UserRecordChangeEvent, UUID] =
      // Side effect that produces id is outside the `source` scope. Thus it remains pure.
      // In other words "id" value remain unchanged if source executed more then once (in case of a retry for example).
      for
        u <- createUser(UUID.randomUUID(), email)
        id <- changeBirthdate(birthDate).inspect(_.id)
      yield id
```

### Example of a simple program and its execution
Using code above we can write a simple program and run it:
```scala 3
val program =
  for
    _ <- UserAggregate.createCompleteUser("test@email.com", LocalDate.of(1970, 1, 1))
    _ <- UserAggregate.addAddress("United States", "10001", "1 Main str", state = Some("NY"))
    _ <- UserAggregate.updateEmail("newemail@gmail.com")
  yield ()

program.run.map: (events, finalState, out) =>
  println(events.zipWithIndex.map(l => s"${l._2}: ${l._1}").mkString("\n"))
  println("\n")
  println(finalState)

```
Which produces the following output:
```terminaloutput
0: UserCreated(904a8dd1-87c5-47f5-9f2a-5c3a403a4b68,test@email.com)
1: BirthdateUpdated(1970-01-01)
2: AddressAdded(Address(United States,10001,1 Main str,None,Some(NY)))
3: EmailUpdated(newemail@gmail.com)

UserRecord(904a8dd1-87c5-47f5-9f2a-5c3a403a4b68,newemail@gmail.com,Some(1970-01-01),Vector(Address(United States,10001,1 Main str,None,Some(NY))))
```
