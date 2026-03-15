package shindy.eventstore

import cats.data.ReaderT
import shindy.SourcedEval

import java.util.UUID

/** Wraps newly created or loaded from EventStore [[shindy.SourcedCreation]].
  *
  * @tparam S
  *   Type of the state
  * @tparam E
  *   Type of the event
  * @tparam A
  *   Output value type
  */
trait Hydrated[S, E, A, F[_]]:

  def map[B](f: A => B): Hydrated[S, E, B, F]

  def update[B](f: A => SourcedEval[S, S, E, B]): Hydrated[S, E, B, F]

  final def update[B](su: SourcedEval[S, S, E, B]): Hydrated[S, E, B, F] =
    update(_ => su)

  def persist(): ReaderT[F, EventStore[E, S, F], (UUID, S, A)]

  /** Simply computes the current state from the events and any [[shindy.SourcedEval]] added using ''update'' method.
    * Not very useful except for using in tests or for debugging.
    */
  def state(): ReaderT[F, EventStore[E, S, F], S]
