package vkdumper

import java.io.File

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{
  BeforeAndAfterAll,
  BeforeAndAfterEachTestData,
  FlatSpec,
  Matchers
}
import vkdumper.Utils.CachedMsgProgress

import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration._

class DBClass
    extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
    with LazyLogging {

  implicit val sys: ActorSystem = ActorSystem()

  def await[T](a: Awaitable[T]): T = Await.result(a, 5.seconds)
  def awaitU(as: Awaitable[Any]*): Unit = as.foreach(await)

  def cmsg(rng: (Int, Int)*): CachedMsgProgress = {
    val l = rng.map { case (x, y) => x.toLong -> y.toLong }.toList
    CachedMsgProgress(l)
  }

  val dbp = DBDefault //DBMem
  val db = new DB(FilePath(1, "tests_temp", dbp))

  val cleanUpFilesAfter = true

  def cleanUp(): Unit = {
    val ud = db.fp.uidRootFile
    ud.listFiles.foreach(_.delete)
    ud.delete()
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

}
