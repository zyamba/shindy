package shindy.eventstore.postgres

import io.circe.Json

private[postgres] case class StateSnapshot(
  version: Int, stateSnapshot: Json, createdAt: java.time.LocalDateTime)
