package shindy.eventstore

import cats.data.ReaderT
import shindy.SourcedUpdate

import java.util.UUID

/** Wraps newly created or loaded from EventStore [[shindy.SourcedCreation]].
  *
  * @tparam STATE
  *   Type of the state
  * @tparam EVENT
  *   Type of the event
  * @tparam A
  *   Output value type
  */
trait Hydrated[STATE, EVENT, A, F[_]] {

  def map[B](f: A => B): Hydrated[STATE, EVENT, B, F]

  def update[B](f: A => SourcedUpdate[STATE, EVENT, B]): Hydrated[STATE, EVENT, B, F]

  final def update[B](su: SourcedUpdate[STATE, EVENT, B]): Hydrated[STATE, EVENT, B, F] =
    update(_ => su)

  def persist(): ReaderT[F, EventStore[EVENT, STATE, F], (UUID, STATE, A)]

  /** Simply computes the current state from the events and any [[shindy.SourcedUpdate]] added using ''update'' method.
    * Not very useful except for using in tests or for debugging.
    */
  def state(): ReaderT[F, EventStore[EVENT, STATE, F], STATE]
}
