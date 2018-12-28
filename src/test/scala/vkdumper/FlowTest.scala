package vkdumper

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import Utils._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import vkdumper.ApiData.{ApiConvId, ApiConvListItem, ApiConversation, ApiMessage}

import scala.collection.JavaConverters._
import scala.util.Random

class FlowTest
    extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
      with BeforeAndAfterEach
    with LazyLogging {

  implicit val sys: ActorSystem = ActorSystem()
  val rnd = new Random()

  def rndstr(ln: Int) =
    (0 until ln)
      .map(_ => (32 + rnd.nextInt(94)).toChar)
      .mkString

  val mo = MockingOpts()
  val api = new ApiMock(mo)
  var db: DBMock = _
  var flow: DumperFlow = _

  override def afterAll(): Unit = {
    super.afterAll()
    logger.info("clean up [end]")
    db.close()
    awaitU(sys.terminate())
  }

  override def beforeEach(): Unit = {
    api.clearAll()
    respawnDB()
  }

  def respawnDB(): Unit = {
    if (db != null) db.close()
    db = new DBMock(mo)
    flow = new DumperFlow(db, api, mo.cfg)
  }

  def pconv(peer: Int, start: Int, total: Int) =
    Conv(peer, start, total, 0, 1 -> 1)

  def pchunk(peer: Int, total: Int, o: Int, c: Int) =
    Chunk(peer, o, c, ConvPos(total, 1, 1))

  def pmsg =
    ApiMessage(0, 1, rnd.nextInt, 0, 101, "", None, Nil, Nil)

  def pmsg(peer: Int, text: String) =
    ApiMessage(0, 1, rnd.nextInt, 0, peer, text, None, Nil, Nil)

  def pmsg(peer: Int, id: Int, text: String) =
    ApiMessage(0, 1, id, 0, peer, text, None, Nil, Nil)

  def pmany(peer: Int, count: Int) =
    (1 to count)
      .map(c => ApiMessage(0, 1, c, 0, peer, s"$peer-$c", None, Nil, Nil))
      .toList

  def pconvItem(msg: ApiMessage)(peers: Int*) =
    peers.map(p => ApiConvListItem(ApiConversation(ApiConvId("chat", p), None), msg))

  "InputData.Conv" should "produce work chunks" in {

    val input = List(
      pconv(1, 0, 600),
      pconv(2, 0, 435),
      pconv(3, 0, 51),
      pconv(4, 400, 800),
      pconv(5, 12, 400),
      pconv(6, 13, 59)
    )

    val output = List(
      //1
      pchunk(1, 600, 0, 200),
      pchunk(1, 600, 200, 200),
      pchunk(1, 600, 400, 200),
      //2
      pchunk(2, 435, 0, 200),
      pchunk(2, 435, 200, 200),
      pchunk(2, 435, 400, 200),
      //3
      pchunk(3, 51, 0, 200),
      //4
      pchunk(4, 800, 400, 200),
      pchunk(4, 800, 600, 200),
      //5
      pchunk(5, 400, 12, 200),
      pchunk(5, 400, 212, 200),
      //6
      pchunk(6, 59, 13, 200)
    )

    val res = input.flatMap(_.stream.toList)
    res shouldBe output
  }

  behavior of "DumperFlow.convFlow"

  it should "return conversation queue" in {
    val input = pconvItem(pmsg)(1 to 480:_*).toList
    api.pushCv(input)

    val f1 = flow.convFlow(input.length)
    val r1 = await(f1)

    r1.asScala.toList shouldBe input
  }

  behavior of "DumperFlow.msgFlow"

  val msgRaw1 = Array(
    pmany(1, 15),
    pmany(2, 471),
    pmany(3, 200),
    pmany(4, 199),
    pmany(5, 201),
    pmany(6, 213),
    pmany(7, 214)
  )

  it should "save messages to db" in {

    val msgs = msgRaw1.toList.flatten
    msgs.groupBy(_.peer_id).foreach {
      case (p, l) => api.pushMsg(p, l)
    }

    val input = pconvItem(pmsg)(1 to 7:_*)

    val f1 = flow.msgFlow(input.toList)
    awaitU(f1)

    db.msgM.asScala.toList shouldBe msgs
  }


  // should save profiles
  // should save progress
  // should handle errors
  // should filter conversations with "done" progress
  // should restore from single-range "undone" progress
  // (later) should restore from multiple-ranges progress

}
