package vkdumper

import java.io.File

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEachTestData, FlatSpec, Matchers}
import vkdumper.ApiData.{ApiChatSettings, ApiConvId, ApiConversation}
import vkdumper.Utils.CachedMsgProgress
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization._
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration._
import scala.io.Source

class DBTest
    extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
    with LazyLogging {

  implicit val sys: ActorSystem = ActorSystem()
  implicit val formats: Formats = Serialization.formats(NoTypeHints)

  def await[T](a: Awaitable[T]): T = Await.result(a, 5.seconds)
  def awaitU(as: Awaitable[Any]*): Unit = as.foreach(await)

  def cmsg(rng: (Int, Int)*): CachedMsgProgress = {
    val l = rng.map { case (x, y) => x.toLong -> y.toLong }.toList
    CachedMsgProgress(l)
  }

  val dbp = DBDefault //DBMem
  val dbBase = "tests_temp"
  val dbUid = 1

  val db = {
    logger.info("clean up files [start]")
    cleanUp()
    new DB(FilePath(dbUid, dbBase, dbp))
  }

  val cleanUpFilesAfter = false

  def cleanUp(): Unit = {
    val base = new File(dbBase)
    val ud = new File(base, s"$dbUid")
    if (base.exists && ud.exists) {
      ud.listFiles.foreach(_.delete)
      ud.delete()
    }
  }

  override def afterAll(): Unit = {
    logger.info("clean up [end]")
    super.afterAll()

    db.close()
    awaitU(sys.terminate())

    if (cleanUpFilesAfter) cleanUp()
  }

  behavior of "DB"

  it should "create dirs on fpath bootstrap" in {
    val fp = db.fp
    val wd = new File(System.getProperty("user.dir"))

    new File(wd, fp.baseDir).exists shouldBe true
    new File(wd, s"${fp.baseDir}/${fp.uid}").exists shouldBe true
  }

  it should "update and return progress" in {

    type LT = (Int, CachedMsgProgress)

    val input = List(
      100 -> cmsg(10 -> 20, 1337 -> 2000),
      101 -> cmsg(1336 -> 1337),
      105 -> cmsg(10 -> 15, 20 -> 30, 40 -> 50),
      108 -> cmsg()
    )

    val input2 = List(
      100 -> cmsg(1 -> 2),
      105 -> cmsg(2 -> 3),
      109 -> cmsg(3 -> 4)
    )

    val ret2 =
      (input.toMap ++ input2).toList

    val addf: LT => Unit = {
      case (p, c) => db.updateProgress(p, c)
    }

    val getf: LT => TraversableOnce[LT] = {
      case (p, _) => db.getProgress(p).map(c => p -> c)
    }

    input.foreach(addf)
    input.flatMap(getf) shouldBe input

    input2.foreach(addf)
    ret2.flatMap(getf) shouldBe ret2

  }

  it should "dump conversation list to file" in {
    val input1 = List(
      ApiConversation(ApiConvId("user", 101), None),
      ApiConversation(ApiConvId("user", 105), None),
      ApiConversation(ApiConvId("chat", 201), Some(ApiChatSettings("foo", 5, "bar", None)))
    )

    val input2 = (1 to 8)
      .map(n => ApiConversation(ApiConvId("user", 300 + n), None))
      .toList


    def readf: String =
      Source.fromFile(db.fp.convLog).getLines().mkString("\n")

    def sr(in: List[ApiConversation]) =
      in.map(write(_)).mkString(",")

    val f1 = db.writeConversations(input1)
    awaitU(f1)
    readf shouldBe sr(input1)

    val f2 = db.writeConversations(input2)
    awaitU(f2)
    readf shouldBe sr(input2)

  }



}
