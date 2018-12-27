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

  val mo = MockingOpts()
  val db = new DB(InMem)
  val api = new ApiMock(mo)
  val flow = new DumperFlow(db, api, mo.cfg)

  override def afterAll(): Unit = {
    logger.info("clean up [end]")
    super.afterAll()

    db.close()
    awaitU(sys.terminate())
  }

}
