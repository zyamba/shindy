package shindy
import cats.data.{IndexedReaderWriterStateT, ReaderWriterStateT}
import cats.instances.either.*
import cats.instances.vector.*

import scala.annotation.unchecked.uncheckedVariance
import scala.language.{implicitConversions, reflectiveCalls}

object SourcedEval:
  def pure[S, E] = new purePartiallyApplied[S, E]

  class purePartiallyApplied[S, E]():
    def apply[A](a: A): SourcedEval[S, S, E, A] =
      SourcedEval(ReaderWriterStateT.pure[MaybeError, Unit, Vector[E], S, A](a))

  extension [IN, S, E, A](self: SourcedEval[IN, S, E, A])
    def map[B](f: A => B): SourcedEval[IN, S, E, B] = SourcedEval(self.widen[E].readerWriterState.map(f))

    def flatMap[B](f: A => SourcedEval[S, S, E, B]): SourcedEval[IN, S, E, B] = self.andThen(f)

/** Sourced update operation with [[A]] as an output. Can be chained using andThen method to create complex operations.
  *
  * Developers should not create this instance directly and instead use DSL provided by [[EventSourced]] object.
  *
  * @param readerWriterState
  *   a program to run
  * @tparam SA
  *   Initial input type to be used to run it
  * @tparam S
  *   Aggregate State type
  * @tparam E
  *   Aggregate Event type
  * @tparam A
  *   Output type
  */
case class SourcedEval[SA, S, +E, +A](
    private val readerWriterState: IndexedReaderWriterStateT[MaybeError, Unit, Vector[
      E @uncheckedVariance
    ], SA, S, A @uncheckedVariance]
):

  /** Widen event type. Useful when using for comprehension instead of `andThen` method:
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
    * val changeBoth: SourcedEval[UserRecord, UserRecord, UserEvent, Unit] = for {
    *   _ <- changeUsername.widen[UserEvent]
    *   _ <- changePassword
    * } yield ()
    * }}}
    * @tparam EB
    *   contravariant event type
    */
  def widen[EB >: E]: SourcedEval[SA, S, EB, A] = this

  /** Inspect current state.
    */
  def inspect[B](f: S => B): SourcedEval[SA, S, E, B] = SourcedEval(this.readerWriterState.inspect(f))

  /** Return current state.
    */
  def get: SourcedEval[SA, S, E, S] = SourcedEval(this.readerWriterState.get)

  /** Run this program with given initial state and return collected events
    *
    * @param initialState
    *   starting state
    */
  def events(initialState: SA): Either[String, Vector[E]] = this.readerWriterState.runL((), initialState)

  /** Run this program with given initial state and return final state
    *
    * @param initialState
    *   starting state
    */
  def state(initialState: SA): Either[String, S] = this.readerWriterState.runS((), initialState)

  /** Run this program with given initial state and return events, final state and resulting value
    *
    * @param initialState
    *   starting state
    */
  def run(initialState: SA): Either[String, (Vector[E], S, A)] = this.readerWriterState.run((), initialState)

  /** Compose two `SourceUpdate` into one
    */
  def andThen[EB >: E, B](other: SourcedEval[S, S, EB, B]): SourcedEval[SA, S, EB, B] =
    andThen[EB, B]((_: A) => other)

  /** Compose two `SourceUpdate` into one
    */
  def andThen[EB >: E, B](other: A => SourcedEval[S, S, EB, B]): SourcedEval[SA, S, EB, B] =
    SourcedEval(this.widen[EB].readerWriterState.flatMap(other(_).readerWriterState))

  /** Modifies state. Only useful for initialization with a snapshot right now.
    */
  private[shindy] def modifyS[SB](block: S => MaybeError[SB]): SourcedEval[SA, SB, E, A] =
    SourcedEval(
      this.readerWriterState.flatMap(a =>
        IndexedReaderWriterStateT: (e, s) =>
          block(s).map(sb => (Vector.empty, sb, a))
      )
    )

  private[shindy] def tell[EB >: E](event: EB): SourcedEval[SA, S, EB, A] =
    SourcedEval(this.widen[EB].readerWriterState.tell(Vector(event)))
