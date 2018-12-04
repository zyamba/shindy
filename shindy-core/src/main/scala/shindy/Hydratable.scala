package shindy

import java.util.UUID

import cats.Monad
import cats.data.Kleisli
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.flatMap._
import shindy.EventSourced.EventHandler

import scala.language.{higherKinds, reflectiveCalls}

trait Hydratable[STATE, EVENT, F[_]] {
  def applyAndSaveChanges[Out](
    sourcedUpdate: SourcedUpdate[STATE, EVENT, Out]
  ): Kleisli[F, EventStore[EVENT, F], Either[String, (UUID, STATE, Out)]]

  def state: Kleisli[F, EventStore[EVENT, F], Either[String, STATE]]
}

object Hydratable {
  def hydrate[STATE, EVENT, F[_]](aggregateId: UUID)(
    implicit eventHandler: EventHandler[STATE, EVENT],
    monadF: Monad[F],
    streamCompiler: fs2.Stream.Compiler[F, F],
  ): Hydratable[STATE, EVENT, F] = {

    new HydratableInt[STATE, EVENT, F](Kleisli { eventStore: EventStore[EVENT, F] =>
      val ev: fs2.Stream[F, EVENT] = eventStore.loadEvents(aggregateId)
      val computedState = ev.fold(Option.empty[STATE]) { case (state, event) =>
        eventHandler(state, event).some
      }.compile.toList
      val stateAsEither = monadF.map(computedState) { fRes =>
        Either.fromOption(fRes.headOption.flatten, "Aggregate events couldn't be found")
      }
      monadF.map(stateAsEither) { e =>
        EventSourced.sourceState[STATE, EVENT](e).map(_ => aggregateId).asRight
      }
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

    override def state: Kleisli[F, EventStore[EVENT, F], Either[String, STATE]] = sourcedCreation.map { creation =>
      creation.flatMap(_.state)
    }
  }
}
