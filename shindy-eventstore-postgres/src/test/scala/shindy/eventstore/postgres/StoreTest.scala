package shindy.eventstore.postgres

import java.util.UUID

import cats.effect.{ContextShift, IO}
import doobie._
import doobie.scalatest.IOChecker
import io.circe.generic.auto._
import org.scalatest.{Tag, _}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader
import shindy.examples.UserService._
import shindy.hydration.Hydration

import scala.concurrent.ExecutionContext

object DatabaseTest extends Tag("DatabaseTest")

object StoreTest {
  case class DatabaseConfig(hostname: String, database: String, username: String,
    password: String, port: Int) {
    lazy val jdbcUrl = s"jdbc:postgresql://$hostname:$port/$database"
  }
}

class StoreTest extends FreeSpec
  with GeneratorDrivenPropertyChecks
  with IOChecker
  with Matchers
  with Hydration[UserRecord, UserRecordChangeEvent] {

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  override def transactor: Transactor[IO] = {
    import StoreTest._

    implicit val configReader: ConfigReader[DatabaseConfig] = deriveReader[DatabaseConfig]
    val dbConf = pureconfig.loadConfigOrThrow[DatabaseConfig]("db")

    Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      dbConf.jdbcUrl,
      dbConf.username,
      dbConf.password
    )
  }

  "Methods tests" - {
    val store = Store.newStore(transactor)
      .forAggregate[UserRecord, UserRecordChangeEvent]("UserAggregate")


    "persist and load events" taggedAs DatabaseTest in {
      forAll { aggId: UUID =>
        val ops = createNew[IO](createUser(aggId, "test@test.com"))
          .update(_ => updateEmail("new-email@test.com"))
        val out = ops.persist(store).unsafeRunSync()
        out should be('right)

        val events = store.loadEvents(aggId).compile.toList.unsafeRunSync()
        events should have size 2
      }
    }
  }

  "SQL statement checks" - {
    import doobie.postgres.implicits._

    "insert event statement " taggedAs DatabaseTest in {
      check(Store.insertEvent)
    }

    "select event statement " taggedAs DatabaseTest in {
      forAll { (id: UUID, fromVersion: Option[Int]) =>
        check(Store.selectEvents(id, fromVersion).query[StoreEvent])
      }
    }
  }
}
