package shindy.eventstore

case class VersionedEvent[+EVENT](event: EVENT, version: Int)
