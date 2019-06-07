package shindy

import scala.language.{higherKinds, implicitConversions, reflectiveCalls}

object SourcedCreation {
  def apply[STATE, EVENT, A](
    create: => Either[String, STATE],
    upd: SourcedUpdate[STATE, EVENT, A]
  ): SourcedCreation[STATE, EVENT, A] = new SourcedCreation(create, upd)
}


class SourcedCreation[STATE, +EVENT, A](
  create: => Either[String, STATE],
  upd: SourcedUpdate[STATE, EVENT, A]
) {

  def adaptEvent[E >: EVENT]: SourcedCreation[STATE, E, A] = this

  def events: Either[String, Vector[EVENT]] = create.flatMap(upd.events)

  def state: Either[String, STATE] = create.flatMap(upd.state)

  def run: Either[String, (Vector[EVENT], STATE, A)] = create.flatMap(upd.run(_))

  def map[B](f: A => B): SourcedCreation[STATE, EVENT, B] = new SourcedCreation[STATE, EVENT, B](create, upd.map(f))

  def andThen[E >: EVENT, B](cont: SourcedUpdate[STATE, E, B]): SourcedCreation[STATE, E, B] = andThen(_ => cont)

  def andThen[E >: EVENT, B](cont: A => SourcedUpdate[STATE, E, B]): SourcedCreation[STATE, E, B] =
    SourcedCreation(create, upd andThen cont)

  def get: SourcedCreation[STATE, EVENT, STATE] = SourcedCreation(this.create, this.upd.get)
}