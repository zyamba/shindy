package shindy.eventstore.postgres

import java.util.UUID

import io.circe.Json

private[postgres] case class StoreEvent(id: Long, aggregateId: UUID, aggregateType: String, aggregateVersion: Int,
  eventBody: Json, eventTimesamp: java.sql.Timestamp)
