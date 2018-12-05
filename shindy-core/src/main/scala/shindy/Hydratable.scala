package shindy

import java.util.UUID

import cats.Monad
import cats.data.Kleisli
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.flatMap._
import cats.syntax.functor._
import shindy.EventSourced.EventHandler

import scala.language.{higherKinds, reflectiveCalls}

trait Hydratable[STATE, EVENT, A, F[_]] {
  def state: Kleisli[F, EventStore[EVENT, F], Either[String, STATE]]

  def update[B](su: SourcedUpdate[STATE, EVENT, B]): Hydratable[STATE, EVENT, B, F] = update(_ => su)

  def update[B](f: A => SourcedUpdate[STATE, EVENT, B]): Hydratable[STATE, EVENT, B, F]

  def persist(store: EventStore[EVENT, F]): F[Either[String, (UUID, STATE, A)]]
}

object Hydratable {
  def hydrate[STATE, EVENT, F[_]](aggregateId: UUID)(
    implicit eventHandler: EventHandler[STATE, EVENT],
    monadF: Monad[F],
    streamCompiler: fs2.Stream.Compiler[F, F],
  ): Hydratable[STATE, EVENT, Unit, F] = {

    new HydratableInternal[STATE, EVENT, Unit, F](Kleisli { eventStore: EventStore[EVENT, F] =>
      val ev: fs2.Stream[F, EVENT] = eventStore.loadEvents(aggregateId)
      val computedState = ev.fold(Option.empty[STATE]) { case (state, event) =>
        eventHandler(state, event).some
      }.compile.toList
      val stateAsEither = computedState.map { fRes =>
        Either.fromOption(fRes.headOption.flatten, "Aggregate events couldn't be found")
      }
      stateAsEither.map { e =>
        EventSourced.sourceState[STATE, EVENT](e).map(_ => (aggregateId, ())).asRight
      }
    })
  }


  def createNew[STATE, EVENT, F[_]](
    sourcedCreation: SourcedCreation[STATE, EVENT, UUID]
  )(implicit Fmonad: Monad[F]): Hydratable[STATE, EVENT, Unit, F] = {
    new HydratableInternal[STATE, EVENT, Unit, F](
      Kleisli.pure(sourcedCreation.map(id => (id, ())).asRight)
    )
  }

  type ResultWithId[A] = (UUID, A)

  private class HydratableInternal[STATE, EVENT, A, F[_]](
    sourcedCreation: Kleisli[F, EventStore[EVENT, F], Either[String, SourcedCreation[STATE, EVENT, ResultWithId[A]]]]
  )(implicit monadF: Monad[F]) extends Hydratable[STATE, EVENT, A, F] {

    override def update[B](f: A => SourcedUpdate[STATE, EVENT, B]): Hydratable[STATE, EVENT, B, F] =
      new HydratableInternal[STATE, EVENT, B, F](
        sourcedCreation.map(_.map {
          creation => creation andThen adaptComposeFn(f)
        })
      )

    override def persist(eventStore: EventStore[EVENT, F]): F[Either[String, (UUID, STATE, A)]] = {
      sourcedCreation.run(eventStore).flatMap {
        _.flatMap {
          _.run.map {
            case (events, state, (id, a)) =>
              eventStore.storeEvents(id, events).map { _ =>
                (id, state, a)
              }
          }
        }.traverse(identity)
      }
    }

    override def state: Kleisli[F, EventStore[EVENT, F], Either[String, STATE]] =
      sourcedCreation.map(_.flatMap(_.state))

    private def adaptComposeFn[B](
      f: A => SourcedUpdate[STATE, EVENT, B]
    ): ResultWithId[A] => SourcedUpdate[STATE, EVENT, ResultWithId[B]]  = {
      case (id, a) => f(a).map(b => (id, b))
    }
  }
}
