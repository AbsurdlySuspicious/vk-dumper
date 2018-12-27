package vkdumper

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import Utils._

class FlowTest
    extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
    with LazyLogging {

  implicit val sys: ActorSystem = ActorSystem()

  val cfg = Cfg(token = "")
  val db = new DB(InMem)
  val api = new ApiOperator(cfg) // todo mock
  val flow = new DumperFlow(db, api, cfg)

  override def afterAll(): Unit = {
    logger.info("clean up [end]")
    super.afterAll()

    db.close()
    awaitU(sys.terminate())
  }

}
