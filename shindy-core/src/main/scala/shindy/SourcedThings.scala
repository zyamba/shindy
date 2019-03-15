package shindy

import cats.data.ReaderWriterStateT
import cats.instances.either._
import cats.instances.vector._

import scala.annotation.unchecked.uncheckedVariance
import scala.language.{higherKinds, implicitConversions, reflectiveCalls}

object SourcedCreation {
  def apply[STATE, EVENT, A](
    create: => Either[String, STATE],
    upd: SourcedUpdate[STATE, EVENT, A]
  ): SourcedCreation[STATE, EVENT, A] = new SourcedCreation(create, upd)
}

object SourcedUpdate {
  def pure[STATE, EVENT] = new purePartiallyApplied[STATE, EVENT]

  def error[STATE, EVENT, Out](msg: String): SourcedUpdate[STATE, EVENT, Out] = {
    SourcedUpdate {
      ReaderWriterStateT.apply[Either[String, ?], Unit, Vector[EVENT], STATE, Out](
        (_, _) => Left(msg)
      )
    }
  }

  class purePartiallyApplied[STATE, EVENT]() {
    def apply[A](a: A): SourcedUpdate[STATE, EVENT, A] = {
      val pureRun = ReaderWriterStateT.pure[Either[String, ?], Unit, Vector[EVENT], STATE, A](a)
      SourcedUpdate(pureRun)
    }
  }

  //noinspection TypeAnnotation
  implicit def SourcedUpdateMonadOps[S, E, A](self: SourcedUpdate[S, E, A]) = new {
    def map[B](f: A => B): SourcedUpdate[S, E, B] = SourcedUpdate(self.adaptEvent[E].run.map(f))

    def flatMap[B](f: A => SourcedUpdate[S, E, B]): SourcedUpdate[S, E, B] = self.andThen(f)
  }

}

class SourcedCreation[STATE, +EVENT, A](
  create: => Either[String, STATE],
  upd: SourcedUpdate[STATE, EVENT, A]
) {

  def adaptEvent[E >: EVENT]: SourcedCreation[STATE, E, A] = this

  def events: Either[String, Vector[EVENT]] = create.flatMap(upd.events)

  def state: Either[String, STATE] = create.flatMap(upd.state)

  def run: Either[String, (Vector[EVENT], STATE, A)] = create.flatMap(upd.run(_))

  def map[B](f: A => B): SourcedCreation[STATE, EVENT, B] = new SourcedCreation[STATE, EVENT, B](create, upd.map(f))

  def andThen[E >: EVENT, B](cont: SourcedUpdate[STATE, E, B]): SourcedCreation[STATE, E, B] = andThen(_ => cont)

  def andThen[E >: EVENT, B](cont: A => SourcedUpdate[STATE, E, B]): SourcedCreation[STATE, E, B] =
    SourcedCreation(create, upd andThen cont)
}

case class SourcedUpdate[STATE, +EVENT, A](
  run: ReaderWriterStateT[Either[String, ?], Unit, Vector[EVENT@uncheckedVariance], STATE, A]) {

  /**
    * Change event type to contravariant E. Useful when using for comprehension instead of `andThen` method:
    * {{{
    * case class UserRecord(...)
    *
    * sealed trait UserEvent
    * case class UsernameChangedEvent(...) extends UserEvent
    * case class PasswordChangedEvent(...) extends UserEvent
    * ...
    *
    * val changeUsername: SourcedUpdate[UserRecord, UsernameChangedEvent, Unit] = ???
    * val changePassword: SourcedUpdate[UserRecord, PasswordChangedEvent, Unit] = ???
    *
    * val changeBoth: SourcedUpdate[UserRecord, UserEvent, Unit] = for {
    *   _ <- changeUsername.adaptEvent[UserEvent]
    *   _ <- changePassword
    * } yield ()
    * }}}
    * @tparam E contravariant event type
    */
  def adaptEvent[E >: EVENT]: SourcedUpdate[STATE, E, A] = this

  /**
    * Inspect current state.
    */
  def inspect[B](f: STATE => B): SourcedUpdate[STATE, EVENT, B] = SourcedUpdate(this.run.inspect(f))

  /**
    * Run this program with given initial state and return collected events
    *
    * @param initialState starting state
    */
  def events(initialState: STATE): Either[String, Vector[EVENT]] = this.run.runL((), initialState)

  /**
    * Run this program with given initial state and return final state
    *
    * @param initialState starting state
    */
  def state(initialState: STATE): Either[String, STATE] = this.run.runS((), initialState)

  /**
    * Run this program with given initial state and return events, final state and resulting value
    *
    * @param initialState starting state
    */
  def run(initialState: STATE): Either[String, (Vector[EVENT], STATE, A)] = this.run.run((), initialState)

  /**
    * Compose two `SourceUpdate` into one
    */
  def andThen[E >: EVENT, B](other: SourcedUpdate[STATE, E, B]): SourcedUpdate[STATE, E, B] = {
    andThen[E, B]((_: A) => other)
  }

  /**
    * Compose two `SourceUpdate` into one
    */
  def andThen[E >: EVENT, B](other: A => SourcedUpdate[STATE, E, B]): SourcedUpdate[STATE, E, B] = {
    SourcedUpdate {
      this.adaptEvent[E].run.flatMap(other(_).run)
    }
  }

  private[shindy] def tell[E >: EVENT](event: E): SourcedUpdate[STATE, E, A] = {
    val contraRun = this.run.transform[Vector[E], STATE, A]((ev, s, a) => (ev, s, a))
    SourcedUpdate(contraRun.tell(Vector(event)))
  }
}