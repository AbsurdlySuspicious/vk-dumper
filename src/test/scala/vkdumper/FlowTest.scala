package vkdumper

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import Utils._
import vkdumper.ApiData.{ApiConvId, ApiConvListItem, ApiConversation, ApiMessage}

import scala.collection.JavaConverters._

class FlowTest
    extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
      with BeforeAndAfterEach
    with LazyLogging {

  implicit val sys: ActorSystem = ActorSystem()

  val mo = MockingOpts()
  val api = new ApiMock(mo)
  var db: DB = _
  var flow: DumperFlow = _

  override def afterAll(): Unit = {
    super.afterAll()
    logger.info("clean up [end]")
    db.close()
    awaitU(sys.terminate())
  }

  override def afterEach(): Unit = {
    api.clearAll()
  }

  def respawnDB(): Unit = {
    if (db != null) db.close()
    db = new DB(InMem)
    flow = new DumperFlow(db, api, mo.cfg)
  }

  def c(peer: Int, start: Int, total: Int) =
    Conv(peer, start, total, 0, 1 -> 1)

  def h(peer: Int, total: Int, o: Int, c: Int) =
    Chunk(peer, o, c, ConvPos(total, 1, 1))

  def m =
    ApiMessage(0, 1, 1, 0, 101, "", None, Nil, Nil)

  def ac(msg: ApiMessage)(peers: Int*) =
    peers.map(p => ApiConvListItem(ApiConversation(ApiConvId("chat", p), None), msg))

  "InputData.Conv" should "produce work chunks" in {

    val input = List(
      c(1, 0, 600),
      c(2, 0, 435),
      c(3, 0, 51),
      c(4, 400, 800),
      c(5, 12, 400),
      c(6, 13, 59)
    )

    val output = List(
      //1
      h(1, 600, 0, 200),
      h(1, 600, 200, 200),
      h(1, 600, 400, 200),
      //2
      h(2, 435, 0, 200),
      h(2, 435, 200, 200),
      h(2, 435, 400, 200),
      //3
      h(3, 51, 0, 200),
      //4
      h(4, 800, 400, 200),
      h(4, 800, 600, 200),
      //5
      h(5, 400, 12, 200),
      h(5, 400, 212, 200),
      //6
      h(6, 59, 13, 200)
    )

    val res = input.flatMap(_.stream.toList)
    res shouldBe output
  }

  behavior of "DumperFlow.convFlow"

  it should "return conversation queue" in {
    respawnDB()

    val input = ac(m)(1 to 480:_*).toList
    api.pushCv(input)

    val f1 = flow.convFlow(input.length)
    val r1 = await(f1)

    r1.asScala.toList shouldBe input
  }

  behavior of "DumperFlow.msgFlow"

  // should return message list
  // should save messages to db
  // should save profiles
  // should save progress
  // should filter conversations with "done" progress
  // should restore from single-range "undone" progress
  // (later) should restore from multiple-ranges progress

}
