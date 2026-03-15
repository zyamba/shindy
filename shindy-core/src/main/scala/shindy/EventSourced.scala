package shindy

import cats.data.{IndexedReaderWriterStateT, ReaderWriterStateT}
import cats.instances.either.*
import cats.syntax.option.*
import shindy.EventSourced.EventHandler

import scala.language.reflectiveCalls
import scala.reflect.ClassTag

type MaybeError[A] = Either[String, A]

object EventSourced:
  type EventHandler[S, E] = (S | Null, E) => S

  object EventHandler:
    // noinspection ConvertExpressionToSAM
    def apply[S, E](fn: PartialFunction[(S | Null, E), S]): EventHandler[S, E] = new EventHandler[S, E]:
      override def apply(s: S | Null, e: E): S =
        if fn.isDefinedAt((s, e)) then fn((s, e))
        else sys.error(s"Unhandled event $e for state $s")

  /** Builds SourcedCreation from `MaybeError[EVENT]`
    */
  def sourceNew[STATE] = new sourceNewPartiallyApplied[STATE]()

  /** Builds SourcedEval from `STATE => MaybeError[EVENT]`
    * @param block
    *   Block of code that maybe produces an Event
    * @param eventHandler
    *   Event handler
    * @tparam S
    *   State type
    * @tparam E
    *   Event type
    * @return
    *   SourcedEval[STATE, EVENT, Unit] from given block.
    */
  def source[S, E](block: S => MaybeError[E])(using
      eventHandler: EventHandler[S, E]
  ): SourcedEval[S, S, E, Unit] = sourceOut(block(_).map((_, ())))

  /** Builds SourcedEval that always reports error
    *
    * @param msg
    *   Error message
    */
  def sourceError[S, E](msg: String): SourcedEval[S, S, E, Nothing] =
    SourcedEval {
      ReaderWriterStateT[MaybeError, Unit, Vector[E], S, Nothing]((_, _) => Left(msg))
    }

  /** Similar to `source` but allows returning extra value that can be pushed to next step when using `andThen`
    * composition.
    */
  def sourceOut[S, E, B](block: S => MaybeError[(E, B)])(using
      eventHandler: EventHandler[S, E]
  ): SourcedEval[S, S, E, B] = sourceOutExt(block(_).map { case (ev, out) =>
    (Vector(ev), out)
  })

  /** Similar to `sourceOut` but allows returning many events at once.
    */
  def sourceOutExt[S, E, B](block: S => MaybeError[(Vector[E], B)])(using
      eventHandler: EventHandler[S, E]
  ): SourcedEval[S, S, E, B] = SourcedEval.continue(block)

  /** Conditionally execute update operation.
    *
    * @param predicate
    *   State predicate
    * @param sourcedUpdate
    *   Conditional operation
    */
  def when[S, SB <: S: ClassTag, E, B](
      predicate: SB => Boolean,
      sourcedUpdate: SourcedEval[S, S, E, B]
  ): SourcedEval[S, S, E, Option[B]] =
    val condUpdate: SB => SourcedEval[S, S, E, Option[B]] = {
      case s: SB if predicate(s) => sourcedUpdate.map(_.some)
      case _                     => SourcedEval.pure(None)
    }
    whenStateIs(condUpdate).map(_.flatten)

  /** Conditionally execute given update if the current state of type [[SB]]
    *
    * @param upd
    *   Conditional update operation
    * @tparam SB
    *   Expected state of the state machine
    */
  def whenStateIs[S, SB <: S: ClassTag, E, B](
      upd: SB => SourcedEval[S, S, E, B]
  ): SourcedEval[S, S, E, Option[B]] =
    val nop: SourcedEval[S, S, E, Option[B]] = SourcedEval.pure(None)
    nop.get.flatMap {
      case s: SB => upd(s).map(Option.apply)
      case _     => nop
    }

  /** Builder that helps scala compiler infer event type
    */
  class sourceNewPartiallyApplied[S]:
    def apply[E](block: => MaybeError[E])(using
        eventHandler: EventHandler[S, E]
    ): SourcedEval[Null, S, E, Unit] = SourcedEval.newFromEvent(block.map((_, ())))

/** Trait with aliases to methods in [[EventSourced]] object to avoid specifying types. Useful when working with only
  * one event type [[E]] and state [[S]], like defining methods for the same aggregate.
  */
trait EventSourced[S, E]:
  /** Alias to [[EventSourced$.sourceNewPartiallyApplied.apply]]
    */
  protected def sourceNew(block: => MaybeError[E])(using EventHandler[S, E]): SourcedEval[Null, S, E, Unit] =
    EventSourced.sourceNew[S](block)

  /** Alias to [[EventSourced$.source]]
    */
  protected def source(block: S => MaybeError[E])(using EventHandler[S, E]): SourcedEval[S, S, E, Unit] =
    EventSourced.source[S, E](block)

  /** Alias to [[EventSourced$.sourceError]]
    */
  protected def sourceError(msg: String): SourcedEval[S, S, E, Nothing] = EventSourced.sourceError(msg)

  /** Alias to [[EventSourced$.sourceOut]]
    */
  protected def sourceOut[B](block: S => MaybeError[(E, B)])(using
      eventHandler: EventHandler[S, E]
  ): SourcedEval[S, S, E, B] = EventSourced.sourceOut(block)

  /** Alias to [[EventSourced$.sourceOutExt]]
    */
  protected def sourceOutExt[SB <: S, B](block: S => MaybeError[(Vector[E], B)])(using
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
