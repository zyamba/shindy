package shindy

import cats.Eval
import cats.data.ReaderWriterStateT
import cats.instances.either._

import scala.language.reflectiveCalls

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
    * Builds SourcedUpdate from `STATE => Either[String, EVENT]`
    * @param block Block of code that maybe produces an Event
    * @param eventHandler Event handler
    * @tparam STATE State type
    * @tparam EVENT Event type
    * @return SourcedUpdate[STATE, EVENT, Unit] from given block.
    */
  def source[STATE, EVENT](block: STATE => Either[String, EVENT])(
    implicit eventHandler: EventHandler[STATE, EVENT]
  ): SourcedUpdate[STATE, EVENT, Unit] = sourceOut(block(_).map((_, Unit)))

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
    * @param predicate State predicate
    * @param sourcedUpdate Conditional operation
    */
  def when[STATE, EVENT, B](predicate: STATE => Boolean, sourcedUpdate: SourcedUpdate[STATE, EVENT, B]):SourcedUpdate[STATE, EVENT, Option[B]] = {
    val pureNop = SourcedUpdate.pure[STATE, EVENT](Option.empty[B])
    pureNop.inspect(predicate).flatMap {
      case true => sourcedUpdate.map(Some.apply)
      case false => pureNop
    }
  }

  /**
    * Builder that helps scala compiler infer all types but `STATE`
    */
  class sourceNewPartiallyApplied[STATE] {
    def apply[EVENT](block: => Either[String, EVENT])(
      implicit eventHandler: EventHandler[STATE, EVENT]
    ): SourcedCreation[STATE, EVENT, Unit] = {
      val eventEval = Eval.later(block)
      val stateEval = eventEval.map(_.right.map { ev =>
        eventHandler(Option.empty[STATE], ev)
      })
      val pureNop = SourcedUpdate.pure[STATE, EVENT](())
      // sourceUpdate is not just pure value but it has to hold creation event
      // since the state was originated from event
      val sourceUpdate = pureNop.flatMap[Unit] { _ =>
        eventEval.value match {
          case Left(msg) => SourcedUpdate.error(msg)
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
