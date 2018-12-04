package shindy

import java.util.UUID

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.either._
import cats.syntax.option._
import shindy.EventSourced.EventHandler

trait Hydratable[STATE, EVENT] {
  def applyAndSaveChanges[Out](
    sourcedUpdate: SourcedUpdate[STATE, EVENT, Out]
  ): Kleisli[IO, EventStore[EVENT], (UUID, STATE, Out)]
}

object Hydratable {
  def hydrate[STATE, EVENT](aggregateId: UUID)(
    implicit eventHandler: EventHandler[STATE, EVENT]
  ): Hydratable[STATE, EVENT] =  {

    new HydratableInt[STATE, EVENT](Kleisli { eventStore: EventStore[EVENT] =>
      val computedState = eventStore.loadEvents(aggregateId).compile
        .fold(Option.empty[STATE]) { case (state, event) =>
          eventHandler.apply(state, event).some
        }
      computedState.map(Either.fromOption(_, "Aggregate events couldn't be found")).map { stateEither =>
        EventSourced.sourceState[STATE, EVENT](stateEither).map(_ => aggregateId)
      }
    })
  }


  def createNew[STATE, EVENT, E <: EVENT](
    sourcedCreation: SourcedCreation[STATE, E, UUID]
  ): Hydratable[STATE, EVENT] =
    new HydratableInt(Kleisli.pure(sourcedCreation.adaptEvent[EVENT]))

  private class HydratableInt[STATE, EVENT](
    sourcedCreation: Kleisli[IO, EventStore[EVENT], SourcedCreation[STATE, EVENT, UUID]]
  ) extends Hydratable[STATE, EVENT] {
    override def applyAndSaveChanges[Out](
      sourcedUpdate: SourcedUpdate[STATE, EVENT, Out]
    ): Kleisli[IO, EventStore[EVENT], (UUID, STATE, Out)] =
      sourcedCreation.flatMap { creation =>
        Kleisli { eventStore: EventStore[EVENT] =>
          val program = creation andThen (id => sourcedUpdate.map(o => (id, o)))
          val programResults = program.run.left.map(new Exception(_))
          IO.fromEither(programResults).flatMap { case (events, state, (id, out)) =>
            eventStore.storeEvents(id, events).map(_ => (id, state, out))
          }
        }
      }
  }
}
