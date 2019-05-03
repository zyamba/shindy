package shindy.eventstore.postgres

import java.util.UUID

import cats.Monad
import cats.effect.Bracket
import cats.instances.vector._
import cats.syntax.functor._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.{fragment, update}
import doobie.util.transactor.Transactor
import doobie.util.update.Update
import io.circe.{Decoder, Encoder, Json}
import shindy.hydration.{EventStore, VersionedEvent}

import scala.language.higherKinds

object Store {
  def newStore[M[_] : Monad](xa: Transactor[M]) = new storePartiallyAppiled[M](xa)

  class storePartiallyAppiled[M[_] : Monad](xa: Transactor[M]) {
    def forAggregate[STATE : Decoder : Encoder, EVENT : Decoder : Encoder](aggregateType: String)(
      implicit bracket: Bracket[M, Throwable]
    ) = new Store[STATE, EVENT, M](aggregateType, xa)
  }

  private def selectEvents(aggregateId: UUID): fragment.Fragment =
    sql"select serial_num, aggregate_id, aggregate_type, aggregate_version, event_body, event_time from event" ++
      fr" where aggregate_id = $aggregateId"

  private[postgres] val insertEvent: update.Update[(String, UUID, Int, Json)] =
    Update("insert into event (aggregate_type, aggregate_id, aggregate_version, event_body) values (?,?,?,?)")

  private[postgres] def andVersionGreaterEqualThen(versionInclusive: Int): fragment.Fragment =
    fr"and aggregate_version >= $versionInclusive"

  private[postgres] def selectEvents(aggregateId: UUID, fromVersion: Option[Int]): fragment.Fragment = {
    val versionFilter = fromVersion.map(andVersionGreaterEqualThen)
      .getOrElse(fragment.Fragment.empty)
    selectEvents(aggregateId) ++ versionFilter ++ fr" order by aggregate_version"
  }
}

class Store[STATE: Decoder : Encoder, EVENT: Decoder : Encoder, M[_]](aggregateType: String, xa: Transactor[M])(
  implicit bracket: Bracket[M, Throwable],
  monadM: Monad[M]
) extends EventStore[STATE, EVENT, M] {

  import Store._

  override def loadEvents(aggregateId: UUID, fromVersion: Option[Int]): fs2.Stream[M, VersionedEvent[EVENT]] = {
    selectEvents(aggregateId, fromVersion).query[StoreEvent].stream.transact(xa)
      .map(se => VersionedEvent(Decoder[EVENT].decodeJson(se.eventBody).fold(throw _, identity), se.aggregateVersion))
  }

  override def storeEvents(aggregateId: UUID, events: Vector[VersionedEvent[EVENT]]): M[Unit] = {
    val convertedEvents = events.map(ev => (aggregateType, aggregateId, ev.version, Encoder[EVENT].apply(ev.event)))
    insertEvent.updateMany(convertedEvents)
      .transact(xa)
      .map(_ => ())
  }

  // todo implement snapshot support

  override def loadLatestStateSnapshot(aggregateId: UUID): M[Option[(STATE, Int)]] = monadM.pure(None)

  override def storeSnapshot(aggregateId: UUID, state: STATE, version: Int): M[Unit] = monadM.pure(())
}
