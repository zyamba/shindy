package shindy.eventstore.postgres

import cats.Show
import cats.data.NonEmptyList
import cats.syntax.either.*
import cats.syntax.show.*
import doobie.util.{Get, Put}
import io.circe.{Json, parser}
import org.postgresql.util.PGobject

private[postgres] object JsonSupport:
  given showPGobject: Show[PGobject] = Show.show(_.getValue.take(250))

  given jsonGet: Get[Json] =
    Get.Advanced.other[PGobject](NonEmptyList.of("jsonb")).temap[Json] { o =>
      parser.parse(o.getValue).leftMap(_.show)
    }

  given jsonPut: Put[Json] =
    Put.Advanced.other[PGobject](NonEmptyList.of("jsonb")).tcontramap[Json] { j =>
      val o = new PGobject
      o.setType("jsonb")
      o.setValue(j.noSpaces)
      o
    }
