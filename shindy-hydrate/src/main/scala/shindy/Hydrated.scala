package shindy

import java.util.UUID

import cats.Monad
import cats.data.Kleisli
import cats.syntax.either._
import cats.syntax.functor._
import cats.syntax.option._
import shindy.EventSourced.EventHandler

import scala.language.{higherKinds, reflectiveCalls}

/**
  * Wraps newly created or loaded from EventStore [[shindy.SourcedCreation]].
  * @tparam STATE Type of the state
  * @tparam EVENT Type of the event
  * @tparam A Output value type
  * @tparam F Effect type
  */
trait Hydrated[STATE, EVENT, A, F[_]] {
  def state(eventStore: EventStore[STATE, EVENT, F]): F[Either[String, STATE]]

  def update[B](su: SourcedUpdate[STATE, EVENT, B]): Hydrated[STATE, EVENT, B, F] = update(_ => su)

  def update[B](f: A => SourcedUpdate[STATE, EVENT, B]): Hydrated[STATE, EVENT, B, F]

  def persist(store: EventStore[STATE, EVENT, F]): F[Either[String, (UUID, STATE, A)]]
}

private[shindy] object Hydrated {
  def createNew[STATE, EVENT, F[_] : Monad](
    sourcedCreation: SourcedCreation[STATE, EVENT, UUID]
  ): Hydrated[STATE, EVENT, UUID, F] = {
    HydratedInt(Kleisli.pure(sourcedCreation.map(id => ResultWithIdAndVersion(id, id, None))))
  }

  def hydrate[STATE, EVENT, F[_] : Monad](aggregateId: UUID)(
    implicit eventHandler: EventHandler[STATE, EVENT],
    streamCompiler: fs2.Stream.Compiler[F, F],
  ): Hydrated[STATE, EVENT, Unit, F] = {
    new HydratedInt[STATE, EVENT, Unit, F](loadState(aggregateId).map { stateAndVersionMaybe =>
      val stateMaybe = stateAndVersionMaybe.map(_._1)
      val versionMaybe = stateAndVersionMaybe.map(_._2)
      EventSourced.sourceState[STATE, EVENT](stateMaybe)
        .map(_ => ResultWithIdAndVersion((), aggregateId, versionMaybe.toOption))
    })
  }

  private def loadState[STATE, EVENT, F[_]](aggregateId: UUID)(
    implicit eventHandler: EventHandler[STATE, EVENT],
    evCompiler: fs2.Stream.Compiler[F, F],
    evMonad: Monad[F]
  ): Kleisli[F, EventStore[STATE, EVENT, F], Either[String, (STATE, Int)]] = Kleisli { eventStore: EventStore[STATE, EVENT, F] =>
    // TODO Use snapshots
    eventStore.loadEvents(aggregateId, Option.empty[Int]).fold(Option.empty[(STATE, Int)]) { case (s, event) =>
      eventHandler.apply(s.map(_._1), event.event).some.map(_ -> event.version)
    }.compile.toList.map(_.headOption.flatten)
      .map(Either.fromOption(_, s"Unable to load state for the aggregate with ID=$aggregateId"))
  }

  private case class ResultWithIdAndVersion[A](out: A, aggregateId: UUID, aggregateInitVersion: Option[Int])

  private case class HydratedInt[STATE, EVENT, OUT, F[_] : Monad](
    scLoad: Kleisli[F, EventStore[STATE, EVENT, F], SourcedCreation[STATE, EVENT, ResultWithIdAndVersion[OUT]]],
  ) extends Hydrated[STATE, EVENT, OUT, F] {

    private lazy val scPersist = scLoad.map(_.run).flatMap { maybeResults =>
      Kleisli { evS: EventStore[STATE, EVENT, F] =>
        maybeResults.traverse { case (events, state, r) =>
          val versionedEvents = events.zip(Stream.from(r.aggregateInitVersion.map(_ + 1).getOrElse(0)))
            .map(Function tupled VersionedEvent.apply)

          evS.storeEvents(r.aggregateId, versionedEvents).map(_ => (r.aggregateId, state, r.out))
        }
      }
    }

    override def update[B](f: OUT => SourcedUpdate[STATE, EVENT, B]): Hydrated[STATE, EVENT, B, F] = {
      val newScLoad = this.scLoad.map { sc =>
        sc.andThen(stateAndVersion => f(stateAndVersion.out).map(b => stateAndVersion.copy(out = b)))
      }
      copy(scLoad = newScLoad)
    }

    override def state(eventStore: EventStore[STATE, EVENT, F]): F[Either[String, STATE]] =
      scLoad.apply(eventStore).map(_.state)

    override def persist(store: EventStore[STATE, EVENT, F]): F[Either[String, (UUID, STATE, OUT)]] =
      scPersist(store)
  }
}
