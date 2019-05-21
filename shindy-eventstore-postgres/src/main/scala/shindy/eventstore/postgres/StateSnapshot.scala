package shindy.eventstore.postgres

import io.circe.Json

case class StateSnapshot(version: Int, stateSnapshot: Json, createdAt: java.sql.Timestamp)
