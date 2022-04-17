package shindy.eventstore.postgres

import cats.Eval
import cats.data.Kleisli
import cats.effect.IO
import doobie._
import doobie.implicits.javasql._
import doobie.postgres._
import doobie.postgres.implicits._
import doobie.postgres.pgisimplicits._
import doobie.scalatest.IOChecker
import doobie.util.transactor.Transactor.Aux
import io.circe.generic.auto._
import io.circe.syntax._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}
import shindy.eventstore.postgres.JsonSupport._
import shindy.eventstore.postgres.StoreTest.DatabaseConfig
import shindy.eventstore.{DatabaseTest, EventStoreBehaviors, Hydration}
import shindy.examples.UserService._

import java.sql.Connection
import java.time.{LocalDateTime, ZoneId}
import java.util.{Calendar, UUID}
import scala.language.reflectiveCalls

object StoreTest {
  private val userRecGen = for {
    id <- arbitrary[UUID]
    email <- Gen.alphaStr.suchThat(_.nonEmpty).map(_ + "@test.com")
    arbDate <- Gen.option(arbitrary[Calendar].map(_.toInstant.atZone(ZoneId.systemDefault())).map(_.toLocalDate))
  } yield UserRecord(id, email, arbDate)

  implicit val arbUserRecGen: Arbitrary[UserRecord] = Arbitrary(userRecGen)

  case class DatabaseConfig(hostname: String, database: String, username: String,
    password: String, port: Int) {
    lazy val jdbcUrl = s"jdbc:postgresql://$hostname:$port/$database"
  }
}

trait StoreInitializer {
  private val executeCreateDbScript = Kleisli[IO, Connection, Unit] { connection: Connection =>
    IO {
      val is = getClass.getResourceAsStream("/create_database.sql")
      try {
        val sql = scala.io.Source.fromInputStream(is, "UTF-8").mkString
        connection.prepareStatement(sql).execute()
      } finally {
        is.close()
      }
    }
  }

  val transactorEval: Eval[Aux[IO, Unit]] = Eval.later {
    implicit val configReader: ConfigReader[DatabaseConfig] = deriveReader[DatabaseConfig]
    val dbConf = ConfigSource.default.at("db").loadOrThrow[DatabaseConfig]

    val tx = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      dbConf.jdbcUrl,
      dbConf.username,
      dbConf.password
    )
    tx.exec.apply(executeCreateDbScript)
    tx
  }
}

class StoreTest extends AsyncFreeSpec
  with ScalaCheckPropertyChecks
  with IOChecker
  with EitherValues
  with Matchers
  with Hydration[UserRecord, UserRecordChangeEvent]
  with StoreInitializer
  with EventStoreBehaviors {

  import StoreTest._

  override def transactor: Transactor[IO] = transactorEval.value

  val postgresqlEventStore = Store.newStore(transactor)
    .forAggregate[UserRecord, UserRecordChangeEvent]("UserAggregate")


  "Postgresql event store should" - {
    behave like typicalEventStore(postgresqlEventStore)
  }

  "SQL statement checks" - {

    "insert event statement " taggedAs DatabaseTest in IO {
      check(Store.insertEvent)
    }

    "select event statement " taggedAs DatabaseTest in IO {
      forAll { (id: UUID, fromVersion: Option[Int]) =>
        check(Store.selectEvents(id, fromVersion).query[StoreEvent])
      }
    }

    "insert state statement" taggedAs DatabaseTest in IO {
      forAll(Gen.uuid, userRecGen, Gen.posNum[Int]) { (id, initialState, version) =>
        whenever(version > 0) {
          check(Store.insertState(id, version, initialState.asJson))
        }
      }
    }
  }
}
