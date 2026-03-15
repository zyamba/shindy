package shindy.eventstore

case class VersionedEvent[+E](event: E, version: Int)
