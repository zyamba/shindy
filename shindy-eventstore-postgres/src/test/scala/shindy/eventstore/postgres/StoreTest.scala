package shindy.eventstore.postgres

import java.sql.Timestamp
import java.time.{LocalDate, ZoneId}
import java.util.{Calendar, UUID}

import cats.effect.{ContextShift, IO}
import doobie._
import doobie.scalatest.IOChecker
import io.circe.generic.auto._
import io.circe.syntax._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Tag, _}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader
import shindy.SourcedCreation
import shindy.examples.UserService._
import shindy.hydration.Hydration

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

class StoreTest extends FreeSpec
  with GeneratorDrivenPropertyChecks
  with IOChecker
  with Matchers
  with Hydration[UserRecord, UserRecordChangeEvent] {

  private val snapshotInterval: Int = 10

  import StoreTest._

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  override def transactor: Transactor[IO] = {

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
          snapshot should be('defined)
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
        snapshot shouldNot be('defined)
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
        snapshot should be('defined)
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

  /**
    * Indicates least number of events that need to be produced in order to store a snapshot.
    * By default state snapshots are disabled.
    */
  override protected def stateSnapshotInterval: Option[Int] = Some(snapshotInterval)
}
