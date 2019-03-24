package shindy.eventstore.postgres

import java.util.UUID

import cats.effect.{ContextShift, IO}
import doobie._
import doobie.scalatest.IOChecker
import io.circe.generic.auto._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{DoNotDiscover, FreeSpec, Matchers, Tag}
import shindy.examples.UserService._
import shindy.hydration.Hydration

import scala.concurrent.ExecutionContext

object DbTest extends Tag("shindy.DbTest")

//@DoNotDiscover
class StoreTest extends FreeSpec
  with GeneratorDrivenPropertyChecks
  with IOChecker
  with Matchers
  with Hydration[UserRecord, UserRecordChangeEvent] {

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  override def transactor: Transactor[IO] = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    s"jdbc:postgresql://localhost:5432/postgres",
    "postgres",
    "event-store"
  )

  "Methods tests" - {

    val store =  Store.newStore(transactor)
      .forAggregate[UserRecord, UserRecordChangeEvent]("UserAggregate")

    "storeEvents" taggedAs DbTest in  {
      forAll { aggId: UUID =>
        val ops = createNew[IO](createUser(aggId, "test@test.com"))
          .update(_ => updateEmail("new-email@test.com"))
        val out = ops.persist(store).unsafeRunSync()
        out should be('right)
      }
    }

    "loadEvents" taggedAs DbTest in {
    }

    "check insert statement " taggedAs DbTest in {
      check(Store.insertEvent)
    }

    "check select statement " taggedAs DbTest in {
      check(Store.insertEvent)
    }

  }
}
