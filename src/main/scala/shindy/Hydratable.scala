package shindy

import java.util.UUID

import cats.Monad
import cats.data.Kleisli
import cats.syntax.either._
import cats.syntax.option._
import shindy.EventSourced.EventHandler

import scala.language.higherKinds

trait Hydratable[STATE, EVENT, F[_]] {
  def applyAndSaveChanges[Out](
    sourcedUpdate: SourcedUpdate[STATE, EVENT, Out]
  ): Kleisli[F, EventStore[EVENT, F], Either[String, (UUID, STATE, Out)]]
}

object Hydratable {
  def hydrate[STATE, EVENT, F[_]](aggregateId: UUID)(
    implicit eventHandler: EventHandler[STATE, EVENT],
    monadF: Monad[F],
    streamCompiler: fs2.Stream.Compiler[F, Either[String, ?]],
  ): Hydratable[STATE, EVENT, F] = {

    new HydratableInt[STATE, EVENT, F](Kleisli { eventStore: EventStore[EVENT, F] =>
      val ev = eventStore.loadEvents(aggregateId)
      val computedState: Either[String, Option[STATE]] = ev.compile[F, Either[String, ?], EVENT]
        .fold(Option.empty[STATE]) { case (state, event) =>
          eventHandler.apply(state, event).some
        }
      val compStateAsEither = computedState.map(s => Either.fromOption(s, "Aggregate events couldn't be found"))
      val mappedToSourcedCreation = compStateAsEither.map { stateEither =>
        EventSourced.sourceState[STATE, EVENT](stateEither).map(_ => aggregateId)
      }
      monadF.pure(mappedToSourcedCreation)
    })
  }


  def createNew[STATE, EVENT, F[_]](
    sourcedCreation: SourcedCreation[STATE, EVENT, UUID]
  )(implicit Fmonad: Monad[F]): Hydratable[STATE, EVENT, F] = {
    new HydratableInt[STATE, EVENT, F](Kleisli.pure(sourcedCreation.adaptEvent[EVENT].asRight))
  }

  private class HydratableInt[STATE, EVENT, F[_]](
    sourcedCreation: Kleisli[F, EventStore[EVENT, F], Either[String, SourcedCreation[STATE, EVENT, UUID]]]
  )(implicit monadF: Monad[F]) extends Hydratable[STATE, EVENT, F] {
    override def applyAndSaveChanges[Out](
      sourcedUpdate: SourcedUpdate[STATE, EVENT, Out]
    ): Kleisli[F, EventStore[EVENT, F], Either[String, (UUID, STATE, Out)]] = {

      sourcedCreation.flatMap { creationEither =>
        val programResults = creationEither.flatMap { creationProg =>
          (creationProg andThen (id => sourcedUpdate.map(o => (id, o)))).run
        }

        Kleisli { eventStore: EventStore[EVENT, F] =>
          val savedResults = programResults.map { case (events, state, (id, out)) =>
            monadF.map(eventStore.storeEvents(id, events))(_ => (id, state, out))
          }
          savedResults.traverse(identity)
        }
      }
    }
  }
}
