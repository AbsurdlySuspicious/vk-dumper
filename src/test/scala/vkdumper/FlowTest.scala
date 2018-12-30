package vkdumper

import java.net.SocketException

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.scalatest._
import Utils._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import vkdumper.ApiData.{ApiConvId, ApiConvListItem, ApiConversation, ApiError, ApiMessage, ApiUser, HttpError}
import vkdumper.MockingCommon.CLQ

import scala.collection.JavaConverters._
import scala.util.Random
import scala.language.implicitConversions

class FlowTest
    extends FlatSpec
    with Matchers
    with OptionValues
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

  def pmsgId(id: Int) =
    ApiMessage(0, 1, id, 0, 101, "", None, Nil, Nil)

  def pmsg =
    ApiMessage(0, 1, 301337, 0, 101, "", None, Nil, Nil)

  def pmsg(peer: Int, text: String) =
    ApiMessage(0, 1, rnd.nextInt, 0, peer, text, None, Nil, Nil)

  def pmsg(peer: Int, id: Int, text: String) =
    ApiMessage(0, 1, id, 0, peer, text, None, Nil, Nil)

  val pmanyIdStart = 10000

  def pmany(peer: Int, count: Int) =
    (1 to count)
      .map(
        c =>
          ApiMessage(0,
                     1,
                     pmanyIdStart + c,
                     0,
                     peer,
                     s"$peer-$c",
                     None,
                     Nil,
                     Nil))
      .toList

  def pusers(count: Int) =
    (1 to count)
      .map(c => ApiUser(rndstr(5), "", 100 + c, None))
      .toList

  def pconvItem(msg: ApiMessage)(peers: Int*) =
    peers
      .map(p =>
        ApiConvListItem(ApiConversation(ApiConvId("chat", p), None), msg))
      .toList

  def ppr(last: Int, rng: (Int, Int)*): CachedMsgProgress =
    CachedMsgProgress(rng.toList, last)

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
    val input = pconvItem(pmsg)(1 to 480: _*)
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

    val input = pconvItem(pmsg)(1 to 7: _*)

    val f1 = flow.msgFlow(input)
    awaitU(f1)

    db.msgM.asScala.toList shouldBe msgs
  }

  it should "save profiles" in {

    val users = pusers(10)
    val input = pconvItem(pmsg)(1)

    api.pushMsg(1, pmany(1, 10))
    api.pushUsers(users)

    val f1 = flow.msgFlow(input)
    awaitU(f1)

    db.usersM.asScala.toList shouldBe users

  }

  it should "save progress" in {

    api.pushMsg(1, pmany(1, 15))
    api.pushMsg(2, pmany(2, 235))

    val input1 = pconvItem(pmsg)(1, 2)
    awaitU(flow.msgFlow(input1))

    val r1 = db.getProgress(1)
    val r2 = db.getProgress(2)

    val s = pmanyIdStart
    r1.get shouldBe CachedMsgProgress(0 -> 15 :: Nil, s + 15)
    r2.get shouldBe CachedMsgProgress(0 -> 235 :: Nil, s + 235)

  }

  // should filter conversations with "done" progress   ---| single test
  // should restore from single-range "undone" progress ---|
  // \/ \/ \/

  it should "restore from progress" in {

    val s = pmanyIdStart

    val msg = List(
      pmany(1, 215),
      pmany(2, 215),
      pmany(3, 150),
      pmany(4, 300)
    )

    val prg = List(
      1 -> ppr(s + 215, 0 -> 215),
      2 -> ppr(s + 215, 0 -> 115, 116 -> 215), // todo least offset restore impl
      3 -> ppr(s + 140, 0 -> 150), // todo maybe update msgid on wrong offsets?
      4 -> ppr(s + 200, 0 -> 200)
    )

    val input = pconvItem(pmsgId(s + 215))(1, 2) ::: pconvItem(pmsgId(s + 150))(
      3) ::: pconvItem(pmsgId(s + 300))(4)

    val outMsg = List(
      pmany(4, 300).drop(200)
    )

    val outPrg = prg.toMap ++ List(
      4 -> ppr(s + 300, 0 -> 300)
    )

    msg.foreach(m => api.pushMsg(m.head.peer_id, m))
    prg.foreach { case (p, v) => db.setProgress(p, v) }

    val f1 = flow.msgFlow(input)
    awaitU(f1)

    db.msgM.asScala.toList shouldBe outMsg.flatten
    db.progress.toList shouldBe outPrg.toList.map { case (p, v) => p -> v.stringRepr }

  }

  it should "handle errors" in {

    implicit def clq[T](lst: List[T]): CLQ[T] =
      new CLQ(lst.asJava)

    def t(tr: Int) = tr -> 0

    val msgIn = List(
      1 -> pmany(1, 1),
      2 -> pmany(2, 1),
      3 -> pmany(3, 1)
    )

    val msgOut = List(
      pmany(1, 1),
      pmany(2, 1)
    )


    msgIn.foreach { case (p, m) => api.pushMsg(p, m) }

    api.historyErrors(t(1)) = HttpError(500) :: Nil
    api.historyErrors(t(2)) = new SocketException("foo") :: Nil
    api.historyErrors(t(3)) = ApiError(1337, "") :: Nil

    val f1 = flow.msgFlow(pconvItem(pmsg)(1, 2, 3))
    awaitU(f1)

    db.msgM.asScala.toList shouldBe msgOut.flatten

  }


  // (later) should restore from multiple-ranges progress

}
