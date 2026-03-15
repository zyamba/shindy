package shindy.examples

import cats.syntax.all.*
import shindy.*
import shindy.EventSourced.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import java.time.Duration
import scala.language.postfixOps

object UserService:

  case class DeactivationStatus(reason: String, until: Instant)

  // state
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
      addresses: Vector[Address] = Vector.empty,
      deactivationStatus: Option[DeactivationStatus] = None
  )

  // events
  sealed trait UserRecordChangeEvent
  case class UserCreated(id: UUID, email: String) extends UserRecordChangeEvent
  case class EmailUpdated(newEmail: String) extends UserRecordChangeEvent
  case class BirthdateUpdated(birthdate: LocalDate) extends UserRecordChangeEvent
  case class AddressAdded(newAddress: Address) extends UserRecordChangeEvent
  case class Deactivated(reason: String, until: Instant) extends UserRecordChangeEvent
  case class Reactivated() extends UserRecordChangeEvent

  // state machine
  given EventHandler[UserRecord, UserRecordChangeEvent] = EventHandler {
    case (_, ev: UserCreated) => UserRecord(ev.id, ev.email)

    case (s: UserRecord, ev: EmailUpdated) => s.copy(email = ev.newEmail)

    case (u: UserRecord, BirthdateUpdated(newDate)) => u.copy(birthdate = Some(newDate))

    case (u: UserRecord, AddressAdded(a)) => u.copy(addresses = u.addresses :+ a)

    case (u: UserRecord, Deactivated(reason, until)) =>
      u.copy(deactivationStatus = Some(DeactivationStatus(reason, until)))

    // case (u: UserRecord, _: Reactivated) => u.copy(deactivationStatus = None)
  }

  // UserAggregate implementation
  object UserAggregate extends EventSourced[UserRecord, UserRecordChangeEvent]:
    def createUser(id: UUID, email: String) = sourceNew(UserCreated(id, email).asRight).map(_ => id)

    def updateEmail(email: String) = source: _ =>
      Either.cond(email.contains("@"), EmailUpdated(email), "email is invalid")

    def changeBirthdate(birthdate: LocalDate) = source: _ =>
      Either.cond(
        birthdate.isBefore(LocalDate.of(2018, 1, 1)),
        BirthdateUpdated(birthdate),
        "Too young!"
      )

    def addAddress(
        country: String,
        zip: String,
        strLine1: String,
        strLine2: Option[String] = None,
        state: Option[String] = None
    ) =
      source: _ =>
        Either.cond(
          country.nonEmpty && strLine1.nonEmpty && zip.nonEmpty,
          AddressAdded(Address(country, zip, strLine1, strLine2, state)),
          "Invalid address"
        )

    def deactivate(reason: String, duration: Duration) = sourceOutExt: u =>
      val untilDate = Instant.now.plus(duration)
      u.deactivationStatus match
        case Some(existingDeactivation) =>
          // leave the old block if the old date is after
          if existingDeactivation.until.isAfter(untilDate) then (Vector.empty, ()).asRight
          else (Vector(Deactivated(reason, untilDate)), ()).asRight
        case None => (Vector(Deactivated(reason, untilDate)), ()).asRight

    def reactivate() = source(_ => Reactivated().asRight)

    // composing multiple actions into a single method
    def createCompleteUser(
        email: String,
        birthDate: LocalDate
    ): SourcedEval[Null, UserRecord, UserRecordChangeEvent, UUID] =
      // Side effect that produces id is outside the `source` scope. Thus it remains pure.
      // In other words "id" value remain unchanged if source executed more then once (in case of a retry for example).
      for
        u <- createUser(UUID.randomUUID(), email)
        id <- changeBirthdate(birthDate).inspect(_.id)
      yield id

  def main(args: Array[String]): Unit =
    // example of execution
    val program =
      for
        _ <- UserAggregate.createCompleteUser("test@email.com", LocalDate.of(1970, 1, 1))
        _ <- UserAggregate.addAddress("United States", "10001", "1 Main str", state = Some("NY"))
        _ <- UserAggregate.updateEmail("newemail@gmail.com")
        _ <- UserAggregate.deactivate("fist block", Duration.ofDays(5))
        _ <- UserAggregate.deactivate("second block", Duration.ofDays(3))
        _ <- UserAggregate.deactivate("third block", Duration.ofDays(10))
        _ <- UserAggregate.reactivate()
      yield ()

    /* Prints out:
     * 0: UserCreated(9c857d10-1919-4469-b5aa-57a6a272d985,test@email.com)
     * 1: BirthdateUpdated(1970-01-01)
     * 2: AddressAdded(Address(United States,10001,1 Main str,None,Some(NY)))
     * 3: EmailUpdated(newemail@gmail.com)
     * 4: Deactivated(fist block,2026-03-19T23:22:23.905773Z)
     * 5: Deactivated(third block,2026-03-24T23:22:23.907081Z)
     * 6: Reactivated()
     *
     * UserRecord(9c857d10-1919-4469-b5aa-57a6a272d985,newemail@gmail.com,Some(1970-01-01),Vector(Address(United States,10001,1 Main str,None,Some(NY))),None)
     * */
    program.run.map: (events, finalState, out) =>
      println(events.zipWithIndex.map(l => s"${l._2}: ${l._1}").mkString("\n"))
      println(s"\n$finalState")
