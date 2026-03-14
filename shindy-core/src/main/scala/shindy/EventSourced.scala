package shindy

import cats.data.{IndexedReaderWriterStateT, ReaderWriterStateT}
import cats.instances.either.*
import cats.syntax.option.*
import shindy.EventSourced.EventHandler

import scala.language.reflectiveCalls
import scala.reflect.ClassTag

type MaybeError[A] = Either[String, A]

object EventSourced:
  type EventHandler[S, E] = (Option[S], E) => S

  object EventHandler:
    // noinspection ConvertExpressionToSAM
    def apply[S, E](fn: PartialFunction[(Option[S], E), S]): EventHandler[S, E] = new EventHandler[S, E]:
      override def apply(s: Option[S], e: E): S =
        if fn.isDefinedAt((s, e)) then fn((s, e))
        else sys.error(s"Unhandled event $e for state $s")

  /** Builds SourcedCreation from `Either[String, EVENT]`
    */
  def sourceNew[STATE] = new sourceNewPartiallyApplied[STATE]()

  /** Builds SourcedEval from `STATE => Either[String, EVENT]`
    * @param block
    *   Block of code that maybe produces an Event
    * @param eventHandler
    *   Event handler
    * @tparam STATE
    *   State type
    * @tparam EVENT
    *   Event type
    * @return
    *   SourcedEval[STATE, EVENT, Unit] from given block.
    */
  def source[STATE, EVENT](block: STATE => Either[String, EVENT])(using
      eventHandler: EventHandler[STATE, EVENT]
  ): SourcedEval[STATE, STATE, EVENT, Unit] = sourceOut(block(_).map((_, ())))

  /** Builds SourcedEval that always reports error
    *
    * @param msg
    *   Error message
    */
  def sourceError[STATE, EVENT](msg: String): SourcedEval[STATE, STATE, EVENT, Nothing] =
    SourcedEval {
      ReaderWriterStateT[MaybeError, Unit, Vector[EVENT], STATE, Nothing]((_, _) => Left(msg))
    }

  /** Similar to `source` but allows returning extra value that can be pushed to next step when using `andThen`
    * composition.
    */
  def sourceOut[STATE, EVENT, B](block: STATE => Either[String, (EVENT, B)])(using
      eventHandler: EventHandler[STATE, EVENT]
  ): SourcedEval[STATE, STATE, EVENT, B] = sourceOutExt(block(_).map { case (ev, out) =>
    (Vector(ev), out)
  })

  /** Similar to `sourceOut` but allows returning many events at once.
    */
  def sourceOutExt[STATE, EVENT, B](block: STATE => Either[String, (Vector[EVENT], B)])(using
      eventHandler: EventHandler[STATE, EVENT]
  ): SourcedEval[STATE, STATE, EVENT, B] = SourcedEval(sourceInternal(block))

  /** Conditionally execute update operation.
    *
    * @param predicate
    *   State predicate
    * @param sourcedUpdate
    *   Conditional operation
    */
  def when[STATE, S <: STATE: ClassTag, EVENT, B](
      predicate: S => Boolean,
      sourcedUpdate: SourcedEval[STATE, STATE, EVENT, B]
  ): SourcedEval[STATE, STATE, EVENT, Option[B]] =
    val condUpdate: S => SourcedEval[STATE, STATE, EVENT, Option[B]] = {
      case s: S if predicate(s) => sourcedUpdate.map(_.some)
      case _                    => SourcedEval.pure(None)
    }
    whenStateIs(condUpdate).map(_.flatten)

  /** Conditionally execute given update if the current state of type [[S]]
    *
    * @param upd
    *   Conditional update operation
    * @tparam S
    *   Expected state of the state machine
    */
  def whenStateIs[STATE, S <: STATE: ClassTag, EVENT, B](
      upd: S => SourcedEval[STATE, STATE, EVENT, B]
  ): SourcedEval[STATE, STATE, EVENT, Option[B]] =
    val nop: SourcedEval[STATE, STATE, EVENT, Option[B]] = SourcedEval.pure(None)
    nop.get.flatMap {
      case s: S => upd(s).map(Option.apply)
      case _    => nop
    }

  /** Builder that helps scala compiler infer event type
    */
  class sourceNewPartiallyApplied[STATE]:
    def apply[EVENT](block: => Either[String, EVENT])(using
        eventHandler: EventHandler[STATE, EVENT]
    ): SourcedEval[Unit, STATE, EVENT, Unit] = SourcedEval(sourceNewInternal((_: Unit) => block))

  /** Produces new SourcedEval initialized using given state
    */
  private[shindy] def sourceWithState[STATE, EVENT](
      block: => Either[String, STATE]
  ): SourcedEval[Unit, STATE, EVENT, Unit] = SourcedEval.pure(()).modifyS(_ => block)

  /** Convert given block to ReaderWriterStateT that can be used by `SourcedEval`
    */
  private def sourceInternal[Out, EVENT, STATE](block: STATE => Either[String, (Vector[EVENT], Out)])(using
      eventHandler: EventHandler[STATE, EVENT]
  ): ReaderWriterStateT[MaybeError, Unit, Vector[EVENT], STATE, Out] = ReaderWriterStateT: (_, startState) =>
    block(startState)
      .map: (events, out) =>
        val finalState = events.foldLeft(startState): (state, event) =>
          eventHandler(Some(state), event)
        (events, finalState, out)

  /** Convert given block to ReaderWriterStateT that can be used by `SourcedEval`
    */
  private def sourceNewInternal[IN, EVENT, STATE](block: IN => Either[String, EVENT])(using
      eventHandler: EventHandler[STATE, EVENT]
  ): IndexedReaderWriterStateT[MaybeError, Unit, Vector[EVENT], IN, STATE, Unit] =
    IndexedReaderWriterStateT: (_, in) =>
      block(in).map: event =>
        val initialState = eventHandler(Option.empty[STATE], event)
        (Vector(event), initialState, ())

/** Trait with aliases to methods in [[EventSourced]] object to avoid specifying types. Useful when working with only
  * one event type [[E]] and state [[S]], like defining methods for the same aggregate.
  */
trait EventSourced[S, E]:
  /** Alias to [[EventSourced$.sourceNewPartiallyApplied.apply]]
    */
  protected def sourceNew(block: => Either[String, E])(using EventHandler[S, E]): SourcedEval[Unit, S, E, Unit] =
    EventSourced.sourceNew[S](block)

  /** Alias to [[EventSourced$.source]]
    */
  protected def source(block: S => Either[String, E])(using EventHandler[S, E]): SourcedEval[S, S, E, Unit] =
    EventSourced.source[S, E](block)

  /** Alias to [[EventSourced$.sourceError]]
    */
  protected def sourceError(msg: String): SourcedEval[S, S, E, Nothing] = EventSourced.sourceError(msg)

  /** Alias to [[EventSourced$.sourceOut]]
    */
  protected def sourceOut[B](block: S => Either[String, (E, B)])(using
      eventHandler: EventHandler[S, E]
  ): SourcedEval[S, S, E, B] = EventSourced.sourceOut(block)

  /** Alias to [[EventSourced$.sourceOutExt]]
    */
  protected def sourceOutExt[B](block: S => Either[String, (Vector[E], B)])(using
      eventHandler: EventHandler[S, E]
  ): SourcedEval[S, S, E, B] = EventSourced.sourceOutExt(block)

  /** Alias to [[EventSourced$.when]]
    */
  protected def when[SB <: S: ClassTag, B](
      predicate: SB => Boolean,
      upd: SourcedEval[S, S, E, B]
  ): SourcedEval[S, S, E, Option[B]] = EventSourced.when(predicate, upd)

  /** Alias to [[EventSourced$.whenStateIs]]
    */
  protected def whenStateIs[SB <: S: ClassTag, B](
      upd: SB => SourcedEval[S, S, E, B]
  ): SourcedEval[S, S, E, Option[B]] = EventSourced.whenStateIs(upd)
