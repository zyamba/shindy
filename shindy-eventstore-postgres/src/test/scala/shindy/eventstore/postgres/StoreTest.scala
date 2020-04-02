package shindy.eventstore.postgres

import java.sql.Connection
import java.time.ZoneId
import java.util.{Calendar, UUID}

import cats.Eval
import cats.data.Kleisli
import cats.effect.{ContextShift, IO}
import doobie._
import doobie.scalatest.IOChecker
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
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader
import shindy.SourcedCreation
import shindy.examples.UserService._
import shindy.hydration.Hydration

import JsonSupport._

import scala.concurrent.ExecutionContext
import scala.language.reflectiveCalls

object DatabaseTest extends Tag("DatabaseTest")

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

class StoreTest extends AnyFreeSpec
  with ScalaCheckPropertyChecks
  with IOChecker
  with EitherValues
  with Matchers
  with Hydration[UserRecord, UserRecordChangeEvent] {

  private val snapshotInterval: Int = 10

  import StoreTest._

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

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

  private val transactorEval: Eval[Aux[IO, Unit]] = Eval.later {
    implicit val configReader: ConfigReader[DatabaseConfig] = deriveReader[DatabaseConfig]
    val dbConf = pureconfig.loadConfigOrThrow[DatabaseConfig]("db")

    val tx = Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      dbConf.jdbcUrl,
      dbConf.username,
      dbConf.password
    )
    tx.exec.apply(executeCreateDbScript).unsafeRunAsyncAndForget()
    tx
  }

  override def transactor: Transactor[IO] = transactorEval.value

  "Methods tests" - {

    lazy val store = Store.newStore(transactor)
      .forAggregate[UserRecord, UserRecordChangeEvent]("UserAggregate")


    "persist and load events" taggedAs DatabaseTest in {
      forAll { aggId: UUID =>
        val ops = createNew[IO](createUser(aggId, "test@test.com"))
          .update(_ => updateEmail("new-email@test.com"))
        val out = ops.persist(store).unsafeRunSync()
        out should be(Symbol("right"))

        val events = store.loadEvents(aggId).compile.toList.unsafeRunSync()
        events should have size 2
      }
    }

    "persist and load state" taggedAs DatabaseTest in {
      forAll { (id: UUID, initialState: UserRecord, newSnapshot: UserRecord, newSnapshotVersion: Int) =>
        whenever(newSnapshotVersion > 0) {
          val insertAndUpdate = for {
            // insert
            n1 <- store.storeSnapshot(id, initialState, 0)
            // overwrite
            n2 <- store.storeSnapshot(id, newSnapshot, newSnapshotVersion)
          } yield n1 + n2

          insertAndUpdate.unsafeRunSync() shouldEqual 2

          val snapshot = store.loadLatestStateSnapshot(id).unsafeRunSync()
          snapshot should be(Symbol("defined"))
          snapshot.get shouldEqual (newSnapshot, newSnapshotVersion)
        }
      }
    }

    "persist should trigger snapshot if snapshot interval not exceeded" taggedAs DatabaseTest in {
      forAll { initialState: UserRecord =>
        val initial: SourcedCreation[UserRecord, UserRecordChangeEvent, UUID] =
          createUser(initialState.id, initialState.email)
        val allOps = (1 until snapshotInterval).foldLeft(createNew[IO](initial).map(_ => ())) { (sc, n) =>
          val scUp = sc.update(updateEmail(s"updated_$n@test.com").map(_ => ()))
          scUp
        }
        allOps.persist(store).unsafeRunSync()

        val snapshot = store.loadLatestStateSnapshot(initialState.id)
          .unsafeRunSync()
        snapshot shouldNot be(Symbol("defined"))

        val events = store.loadEvents(initialState.id).compile.toList.unsafeRunSync()
        events should not be empty
      }
    }

    "persist should trigger snapshot if snapshot interval exceeded" taggedAs DatabaseTest in {
      forAll { initialState: UserRecord =>
        val initial: SourcedCreation[UserRecord, UserRecordChangeEvent, UUID] =
          createUser(initialState.id, initialState.email)
        val allOps = (1 to snapshotInterval).foldLeft(createNew[IO](initial).map(_ => ())) { (sc, n) =>
          val scUp = sc.update(updateEmail(s"updated_$n@test.com").map(_ => ()))
          scUp
        }
        allOps.persist(store).unsafeRunSync()

        val snapshot = store.loadLatestStateSnapshot(initialState.id)
          .unsafeRunSync()
        snapshot should be(Symbol("defined"))

        val events = store.loadEvents(initialState.id).compile.toList.unsafeRunSync()
        events should not be empty
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

    "insert state statement" taggedAs DatabaseTest in {
      forAll { (id: UUID, initialState: UserRecord, version: Int) =>
        whenever(version > 0) {
          check(Store.insertState(id, version, initialState.asJson))
        }
      }
    }
  }

  override protected def stateSnapshotInterval: Option[Int] = Some(snapshotInterval)
}
