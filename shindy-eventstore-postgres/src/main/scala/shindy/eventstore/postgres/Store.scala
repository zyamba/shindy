package shindy.eventstore.postgres

import java.util.UUID

import cats._
import cats.effect.Bracket
import cats.implicits._
import doobie.implicits._
import doobie.implicits.javatime._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import doobie.util.update.Update
import doobie.util.{Put, Read, fragment, update}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import shindy.hydration.{EventStore, VersionedEvent}
import JsonSupport._

import scala.language.higherKinds

object Store {
  def newStore[M[_] : Monad](xa: Transactor[M]) = new storePartiallyAppiled[M](xa)

  class storePartiallyAppiled[M[_] : Monad](xa: Transactor[M]) {
    def forAggregate[STATE: Decoder : Encoder, EVENT: Decoder : Encoder](aggregateType: String)(
      implicit bracket: Bracket[M, Throwable]
    ) = new Store[STATE, EVENT, M](aggregateType, xa)
  }

  private def selectEvents(aggregateId: UUID): fragment.Fragment =
    sql"select serial_num, aggregate_id, aggregate_type, aggregate_version, event_body, event_time from event" ++
      fr" where aggregate_id = $aggregateId"

  private[postgres] val insertEvent: update.Update[(String, UUID, Int, Json)] =
    Update("insert into event (aggregate_type, aggregate_id, aggregate_version, event_body) values (?,?,?,?)")

  private[postgres] def insertState(aggregateId: UUID, version: Int, stateSnapshot: Json): doobie.Update0 =
    sql"""
         insert into state_snapshot (aggregate_id, aggregate_version, state_snapshot,create_time)
         values ($aggregateId,$version,$stateSnapshot,now())
         on conflict (aggregate_id)
         do update set state_snapshot = $stateSnapshot, aggregate_version = $version, create_time = now()
      """.update

  private[postgres] def andVersionGreaterEqualThen(versionInclusive: Int): fragment.Fragment =
    fr"and aggregate_version >= $versionInclusive"

  private[postgres] def selectEvents(aggregateId: UUID, fromVersion: Option[Int]): fragment.Fragment = {
    val versionFilter = fromVersion.map(andVersionGreaterEqualThen)
      .getOrElse(fragment.Fragment.empty)
    selectEvents(aggregateId) ++ versionFilter ++ fr" order by aggregate_version"
  }

  private[postgres] def findStateSnapshot(aggregateId: UUID): fragment.Fragment = {
    sql"select aggregate_version, state_snapshot, create_time from state_snapshot" ++
      fr" where aggregate_id = $aggregateId"
  }
}

class Store[STATE: Decoder : Encoder, EVENT: Decoder : Encoder, M[_]](aggregateType: String, xa: Transactor[M])(
  implicit bracket: Bracket[M, Throwable],
  monadM: Monad[M]
) extends EventStore[STATE, EVENT, M] {

  import Store._

  override def loadEvents(aggregateId: UUID, fromVersion: Option[Int]): fs2.Stream[M, VersionedEvent[EVENT]] = {
    selectEvents(aggregateId, fromVersion).query[StoreEvent].stream.transact(xa)
      .map(se => VersionedEvent(decodeFromJson[EVENT](se.eventBody), se.aggregateVersion))
  }

  override def storeEvents(aggregateId: UUID, events: Vector[VersionedEvent[EVENT]]): M[Unit] = {
    val convertedEvents = events.map(ev => (aggregateType, aggregateId, ev.version, Encoder[EVENT].apply(ev.event)))
    insertEvent.updateMany(convertedEvents)
      .transact(xa)
      .map(_ => ())
  }

  override def loadLatestStateSnapshot(aggregateId: UUID): M[Option[(STATE, Int)]] =
    findStateSnapshot(aggregateId).query[StateSnapshot].map { lastSnapshot =>
      decodeFromJson[STATE](lastSnapshot.stateSnapshot) -> lastSnapshot.version
    }.option.transact(xa)

  override def storeSnapshot(aggregateId: UUID, state: STATE, version: Int): M[Int] = {
    insertState(aggregateId, version, state.asJson).run.transact(xa)
  }

  private def decodeFromJson[T: Decoder](json: Json) = Decoder[T].decodeJson(json).fold(throw _, identity)
}
