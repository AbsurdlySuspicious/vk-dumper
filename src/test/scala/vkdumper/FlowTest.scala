package vkdumper

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import Utils._
import scala.collection.JavaConverters._

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

  val f1 = flow.convFlow(228)
  val r1 = await(f1)

  //println(s"\n${r1.asScala.mkString("\n")}")

  behavior of "DumperFlow.convFlow"

  // should return conversation queue

  behavior of "DumperFlow.msgFlow"

  // chunks test (requests check)
  // should return message list
  // should save messages to db
  // should save profiles
  // should save progress
  // should filter conversations with "done" progress
  // should restore from single-range "undone" progress
  // (later) should restore from multiple-ranges progress

}
