package shindy

import cats.Eval
import cats.data.ReaderWriterStateT
import cats.instances.either._
import cats.syntax.option._
import shindy.EventSourced.EventHandler

import scala.language.reflectiveCalls
import scala.reflect.ClassTag

object EventSourced {
  type EventHandler[S, E] = (Option[S], E) => S

  object EventHandler {
    //noinspection ConvertExpressionToSAM
    def apply[S, E](fn: PartialFunction[(Option[S], E), S]): EventHandler[S, E] = new EventHandler[S, E] {
      override def apply(s: Option[S], e: E): S =
        if (fn.isDefinedAt((s, e))) fn((s, e))
        else sys.error(s"Unhandled event $e for state $s")
    }
  }

  /**
    * Builds SourcedCreation from `Either[String, EVENT]`
    */
  def sourceNew[STATE] = new sourceNewPartiallyApplied[STATE]()

  /**
    * Produces new SourcedCreation without logging any events.
    */
  private[shindy] def sourceState[STATE, EVENT](block: => Either[String, STATE]): SourcedCreation[STATE, EVENT, Unit] =
    SourcedCreation(block, SourcedUpdate.pure(()))

  /**
    * Builds SourcedUpdate from `STATE => Either[String, EVENT]`
    * @param block Block of code that maybe produces an Event
    * @param eventHandler Event handler
    * @tparam STATE State type
    * @tparam EVENT Event type
    * @return SourcedUpdate[STATE, EVENT, Unit] from given block.
    */
  def source[STATE, EVENT](block: STATE => Either[String, EVENT])(
    implicit eventHandler: EventHandler[STATE, EVENT]
  ): SourcedUpdate[STATE, EVENT, Unit] = sourceOut(block(_).map((_, ())))

  /**
    * Builds SourcedUpdate that always reports error
    *
    * @param msg Error message
    */
  def sourceError[STATE, EVENT](msg: String): SourcedUpdate[STATE, EVENT, Nothing] =
    SourcedUpdate {
      ReaderWriterStateT.apply[Either[String, ?], Unit, Vector[EVENT], STATE, Nothing](
        (_, _) => Left(msg)
      )
    }

  /**
    * Similar to `source` but allows returning extra value that can be pushed to next step
    * when using `andThen` composition.
    */
  def sourceOut[STATE, EVENT, Out](block: STATE => Either[String, (EVENT, Out)])(
    implicit eventHandler: EventHandler[STATE, EVENT]
  ): SourcedUpdate[STATE, EVENT, Out] = sourceOutExt(block(_).map { case (ev, out) =>
    (Vector(ev), out)
  })

  /**
    * Similar to `sourceOut` but allows returning many events at once.
    */
  def sourceOutExt[STATE, EVENT, Out](block: STATE => Either[String, (Vector[EVENT], Out)])(
    implicit eventHandler: EventHandler[STATE, EVENT]
  ): SourcedUpdate[STATE, EVENT, Out] = SourcedUpdate(sourceInt(block))

  /**
    * Conditionally execute update operation.
    *
    * @param predicate State predicate
    * @param sourcedUpdate Conditional operation
    */
  def when[STATE, S <: STATE : ClassTag, EVENT, B](
    predicate: S => Boolean,
    sourcedUpdate: SourcedUpdate[STATE, EVENT, B]
  ): SourcedUpdate[STATE, EVENT, Option[B]] = {
    val condUpdate: S => SourcedUpdate[STATE, EVENT, Option[B]] = {
      case s: S if predicate(s) => sourcedUpdate.map(_.some)
      case _ => SourcedUpdate.pure(None)
    }
    whenStateIs(condUpdate).map(_.flatten)
  }

  /**
    * Conditionally execute given update if the current state of type [[S]]
    *
    * @param up Conditional update operation
    * @tparam S Expected state of the state machine
    */
  def whenStateIs[STATE, S <: STATE : ClassTag, EVENT, B](
    up: S => SourcedUpdate[STATE, EVENT, B]
  ): SourcedUpdate[STATE, EVENT, Option[B]] = {
    val nop: SourcedUpdate[STATE, EVENT, Option[B]] = SourcedUpdate.pure(None)
    nop.get.flatMap {
      case s: S => up(s).map(Option.apply)
      case _ => nop
    }
  }

  /**
    * Builder that helps scala compiler infer event type
    */
  class sourceNewPartiallyApplied[STATE] {
    def apply[EVENT](block: => Either[String, EVENT])(
      implicit eventHandler: EventHandler[STATE, EVENT]
    ): SourcedCreation[STATE, EVENT, Unit] = {
      val eventEval = Eval.later(block)
      val stateEval = eventEval.map(_.map { ev =>
        eventHandler(Option.empty[STATE], ev)
      })
      val pureNop = SourcedUpdate.pure[STATE, EVENT](())
      // sourceUpdate is not just pure value but it has to hold creation event
      // since the state was originated from an event
      val sourceUpdate = pureNop.flatMap[Unit] { _ =>
        eventEval.value match {
          case Left(msg) => sourceError(msg)
          case Right(ev) => pureNop.tell(ev)
        }
      }
      SourcedCreation(stateEval.value, sourceUpdate)
    }
  }

  /**
    * Convert given block to ReaderWriterStateT that can be used by `SourcedUpdate`
    */
  private def sourceInt[Out, EVENT, STATE](block: STATE => Either[String, (Vector[EVENT], Out)])(
    implicit eventHandler: EventHandler[STATE, EVENT]
  ) = ReaderWriterStateT.apply[Either[String, ?], Unit, Vector[EVENT], STATE, Out] { (_, startState) =>
    block(startState).map { case (events, out) =>
      val finalState = events.foldLeft(startState) { case (state, event) =>
        eventHandler(Some(state), event)
      }
      (events, finalState, out)
    }
  }
}
