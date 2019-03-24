package shindy.eventstore

import java.util.UUID

import doobie.util.Meta
import io.circe.{Json, ParsingFailure, parser}
import org.postgresql.util.PGobject

package object postgres {
  private val jsonbTypeName = "jsonb"

  @throws[ParsingFailure]("if the json cannot be parsed")
  private def parseFromJsonb(obj: PGobject): Json =
    parser.parse(obj.getValue).fold(throw _, identity)

  private def convertAsJsonb(json: Json): PGobject = {
    val obj = new PGobject
    obj.setType(jsonbTypeName)
    obj.setValue(json.noSpaces)
    obj
  }

  implicit val posgresJsonMeta: Meta[Json] =
    Meta.Advanced.other[PGobject](jsonbTypeName).imap[Json](parseFromJsonb)(convertAsJsonb)
}
