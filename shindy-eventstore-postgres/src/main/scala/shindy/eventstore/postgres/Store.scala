package shindy.eventstore.postgres

import cats.Monad
import cats.effect._
import cats.implicits._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import doobie.util.update.Update
import doobie.util.{Read, fragment, update}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import shindy.eventstore.{EventStore, VersionedEvent}
import shindy.eventstore.postgres.JsonSupport._

import java.time.LocalDateTime
import java.util.UUID

object Store {
  def newStore[F[_] : MonadCancelThrow](xa: Transactor[F]) = new storePartiallyAppiled(xa)

  class storePartiallyAppiled[F[_] : Monad : MonadCancelThrow](xa: Transactor[F]) {
    def forAggregate[STATE: Decoder : Encoder, EVENT: Decoder : Encoder](aggregateType: String) =
      new StoreZ[STATE, EVENT, F](aggregateType, xa)
  }

  private def selectEvents(aggregateId: UUID): fragment.Fragment =
    sql"select serial_num, aggregate_id, aggregate_type, aggregate_version, event_body, event_time from event" ++
      fr" where aggregate_id = $aggregateId"
  private[postgres] val insertEvent: update.Update[(String, UUID, Int, Json)] =
    Update.apply[(String, UUID, Int, Json)]("insert into event (aggregate_type, aggregate_id, aggregate_version, event_body) values (?,?,?,?)")

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

class StoreZ[STATE: Decoder : Encoder, EVENT: Decoder : Encoder, F[_] : Monad : MonadCancelThrow](
  aggregateType: String, transactor: Transactor[F]
) extends EventStore[EVENT, STATE, F] {

  import Store._

  implicitly[Read[UUID]]
  implicitly[Read[LocalDateTime]]
  implicitly[Read[Json]]
  implicitly[Read[StoreEvent]]

  override def loadEvents(aggregateId: UUID, fromVersion: Option[Int]): fs2.Stream[F, VersionedEvent[EVENT]] =
    selectEvents(aggregateId, fromVersion).query[StoreEvent]
      .stream.transact(transactor)
      .map(se => VersionedEvent(decodeFromJson[EVENT](se.eventBody), se.aggregateVersion))

  override def storeEvents(aggregateId: UUID, events: Vector[VersionedEvent[EVENT]]): F[Unit] = {
    val convertedEvents = events.map(ev => (aggregateType, aggregateId, ev.version, Encoder[EVENT].apply(ev.event)))
    insertEvent.updateMany(convertedEvents)
      .transact(transactor)
      .map(_ => ())
  }

  override def loadLatestStateSnapshot(aggregateId: UUID): F[Option[(STATE, Int)]] =
    findStateSnapshot(aggregateId).query[StateSnapshot].map { lastSnapshot =>
      decodeFromJson[STATE](lastSnapshot.stateSnapshot) -> lastSnapshot.version
    }.option.transact(transactor)

  override def storeSnapshot(aggregateId: UUID, state: STATE, version: Int): F[Int] =
    insertState(aggregateId, version, state.asJson).run.transact(transactor)

  private def decodeFromJson[T: Decoder](json: Json): T =
    Decoder[T].decodeJson(json).fold(throw _, identity)
}
