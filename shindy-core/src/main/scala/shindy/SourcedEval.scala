package shindy
import cats.data.{IndexedReaderWriterStateT, ReaderWriterStateT}
import cats.instances.either.*
import cats.instances.vector.*
import shindy.EventSourced.EventHandler

import scala.annotation.unchecked.uncheckedVariance
import scala.language.{implicitConversions, reflectiveCalls}

object SourcedEval:
  def pure[S, E] = new purePartiallyApplied[S, E]

  class purePartiallyApplied[S, E]:
    def apply[A](a: A): SourcedEval[S, S, E, A] =
      SourcedEval(ReaderWriterStateT.pure[MaybeError, Unit, Vector[E], S, A](a))

  def continue[S, E, Out](block: S => MaybeError[(Vector[E], Out)])(using
      eventHandler: EventHandler[S, E]
  ): SourcedEval[S, S, E, Out] = SourcedEval[S, S, E, Out](
    IndexedReaderWriterStateT((_, sa) =>
      block(sa).map: (events, out) =>
        val nextState = events.foldLeft(sa)(eventHandler.apply)
        (events, nextState, out)
    )
  )

  private[shindy] def newFromState[S, E](block: => MaybeError[S]): SourcedEval[Null, S, E, Unit] =
    SourcedEval[Null, S, E, Unit](
      IndexedReaderWriterStateT((_, _) =>
        block.map: state =>
          (Vector.empty, state, ())
      )
    )

  private[shindy] def newFromEvent[S, E, Out](block: => MaybeError[(E, Out)])(using
      eventHandler: EventHandler[S, E]
  ): SourcedEval[Null, S, E, Out] = SourcedEval[Null, S, E, Out](
    IndexedReaderWriterStateT((_, _) =>
      block.map: (event, out) =>
        val initialState = eventHandler(null, event)
        (Vector(event), initialState, out)
    )
  )

  extension [IN, S, E, A](self: SourcedEval[IN, S, E, A])
    def map[B](f: A => B): SourcedEval[IN, S, E, B] = self.mapInt(f)

    def flatMap[B](f: A => SourcedEval[S, S, E, B]): SourcedEval[IN, S, E, B] = self.andThen(f)

    /** Run this program with given initial state and return events, final state and resulting value
      *
      * @param initialState
      *   starting state
      */
    def run(initialState: IN): Either[String, (Vector[E], S, A)] = self.runInternal(initialState)

  extension [S, E, A](self: SourcedEval[Null, S, E, A])
    /** Run this program and return events, final state and resulting value
      */
    def run: Either[String, (Vector[E], S, A)] = self.runInternal(null)

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
    * val changeUsername: SourcedEval[UserRecord, UserRecord, UsernameChangedEvent, Unit] = ???
    * val changePassword: SourcedEval[UserRecord, UserRecord, PasswordChangedEvent, Unit] = ???
    *
    * val changeBoth: SourcedEval[UserRecord, UserRecord, UserEvent, Unit] = for {
    *   _ <- changeUsername.widen[UserEvent]
    *   _ <- changePassword
    * } yield ()
    * }}}
    * @tparam EB
    *   contravariant event type
    */
  private def widen[EB >: E]: SourcedEval[SA, S, EB, A] = this

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

  /** Compose two `SourceUpdate` into one
    */
  def andThen[EB >: E, B](next: SourcedEval[S, S, EB, B]): SourcedEval[SA, S, EB, B] =
    andThen[EB, B]((_: A) => next)

  /** Compose two `SourceUpdate` into one
    */
  def andThen[EB >: E, B](next: A => SourcedEval[S, S, EB, B]): SourcedEval[SA, S, EB, B] = flatMapInt(next)

  private def runInternal(initialState: SA): Either[String, (Vector[E], S, A)] =
    this.readerWriterState.run((), initialState)

  private def flatMapInt[EB >: E, B](next: A => SourcedEval[S, S, EB, B]) =
    SourcedEval(this.widen[EB].readerWriterState.flatMap(next(_).readerWriterState))

  private def mapInt[EB >: E, B](f: A => B) =
    SourcedEval(this.widen[EB].readerWriterState.map(f))
