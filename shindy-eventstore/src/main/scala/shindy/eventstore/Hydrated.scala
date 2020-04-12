package shindy.eventstore

import java.util.UUID

import shindy.SourcedUpdate
import zio.RIO

/**
  * Wraps newly created or loaded from EventStore [[shindy.SourcedCreation]].
  *
  * @tparam STATE Type of the state
  * @tparam EVENT Type of the event
  * @tparam A     Output value type
  */
trait Hydrated[STATE, EVENT, A] {

  def map[B](f: A => B): Hydrated[STATE, EVENT, B]

  def update[B](f: A => SourcedUpdate[STATE, EVENT, B]): Hydrated[STATE, EVENT, B]

  final def update[B](su: SourcedUpdate[STATE, EVENT, B]): Hydrated[STATE, EVENT, B] =
    update(_ => su)

  def persist(): RIO[EventStore[EVENT, STATE], (UUID, STATE, A)]

  /**
    * Simply computes the current state from the events and any [[shindy.SourcedUpdate]]
    * added using ''update'' method.
    * Not very useful except for using in tests or for debugging.
    */
  def state(): RIO[EventStore[EVENT, STATE], STATE]
}