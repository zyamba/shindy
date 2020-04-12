package shindy.eventstore.postgres

import cats.Show
import cats.data.NonEmptyList
import cats.syntax.either._
import cats.syntax.show._
import doobie.util.{Get, Put}
import io.circe.{Json, parser}
import org.postgresql.util.PGobject

private[postgres] object JsonSupport {
  implicit val showPGobject: Show[PGobject] = Show.show(_.getValue.take(250))

  implicit val jsonGet: Get[Json] =
    Get.Advanced.other[PGobject](NonEmptyList.of("jsonb")).temap[Json] { o =>
      parser.parse(o.getValue).leftMap(_.show)
    }

  implicit val jsonPut: Put[Json] =
    Put.Advanced.other[PGobject](NonEmptyList.of("jsonb")).tcontramap[Json] {
      j =>
        val o = new PGobject
        o.setType("jsonb")
        o.setValue(j.noSpaces)
        o
    }
}
