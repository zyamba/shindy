package shindy.eventstore.postgres

import java.sql.Connection
import java.time.ZoneId
import java.util.{Calendar, UUID}

import cats.Eval
import cats.data.Kleisli
import cats.effect.{Async, ContextShift, Effect, IO}
import doobie._
import doobie.scalatest.{Checker, IOChecker}
import doobie.implicits.javatime._
import doobie.util.transactor.Transactor.Aux
import io.circe.generic.auto._
import io.circe.syntax._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.generic.semiauto.deriveReader
import shindy.SourcedCreation
import shindy.examples.UserService._
import shindy.eventstore.{DatabaseTest, EventStoreBehaviors, Hydration}
import JsonSupport._
import shindy.eventstore.postgres.StoreTest.DatabaseConfig
import zio.{RIO, Task, UIO, URIO, ZIO}
import zio.interop.catz._

import scala.concurrent.ExecutionContext
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
  private val executeCreateDbScript = Kleisli[Task, Connection, Unit] { connection: Connection =>
    Task {
      val is = getClass.getResourceAsStream("/create_database.sql")
      try {
        val sql = scala.io.Source.fromInputStream(is, "UTF-8").mkString
        connection.prepareStatement(sql).execute()
      } finally {
        is.close()
      }
    }
  }

  val transactorEval: Eval[Aux[Task, Unit]] = Eval.later {
    implicit val configReader: ConfigReader[DatabaseConfig] = deriveReader[DatabaseConfig]
    val dbConf = ConfigSource.default.at("db").loadOrThrow[DatabaseConfig]

    val tx = Transactor.fromDriverManager[Task](
      "org.postgresql.Driver",
      dbConf.jdbcUrl,
      dbConf.username,
      dbConf.password
    )
    zio.Runtime.default.unsafeRunSync(tx.exec.apply(executeCreateDbScript))
    tx
  }
}

class StoreTest extends AnyFreeSpec
  with ScalaCheckPropertyChecks
  with Checker[Task]
  with EitherValues
  with Matchers
  with Hydration[UserRecord, UserRecordChangeEvent]
with StoreInitializer
  with EventStoreBehaviors {

  import StoreTest._

  override implicit def M: Effect[Task] = taskEffectInstance[Any](zio.Runtime.default)

  override def transactor: Transactor[Task] = transactorEval.value

  val postgresqlEventStore = Store.newStore(transactor)
    .forAggregate[UserRecord, UserRecordChangeEvent]("UserAggregate")


  "Postgresql event store should" - {
    behave like typicalEventStore(postgresqlEventStore)
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

    "insert state statement" taggedAs DatabaseTest in {
      forAll(Gen.uuid, userRecGen, Gen.posNum[Int]) { (id, initialState, version) =>
        whenever(version > 0) {
          check(Store.insertState(id, version, initialState.asJson))
        }
      }
    }
  }

}
