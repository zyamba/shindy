package shindy

import zio.Has

package object eventstore {
  type EventStore[E, S] = Has[EventStore.DefinedFor[E, S]]
}
