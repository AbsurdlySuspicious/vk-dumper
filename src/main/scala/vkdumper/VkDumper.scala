package vkdumper

import java.io.File

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging

import scala.io.{Codec, Source}
import scala.language.postfixOps
import Utils._

import scala.concurrent.duration._
import scala.runtime.ScalaRunTime

import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization._
import org.json4s._
import org.json4s.jackson.JsonMethods._

object VkDumper extends App with LazyLogging {

  implicit val codec: Codec = Codec.UTF8

  val usage = """
                |Usage:
                |  -c FILE : Provide config as file
                |  -i CFG  : Provide inline config as string
                |  -g      : Generate default config to stdout
              """.stripMargin

  def esc(m: String, code: Int = 1): Nothing = {
    println(m)
    System.exit(code)
    throw new Exception("trap")
  }

  def esc(code: Int): Nothing = esc("", code)

  val cfgJ = args.toList match {
    case "-c" :: path :: Nil =>
      val f = new File(path)
      if (!f.exists) esc(s"No file ${f.getName} exists")
      if (!f.isFile) esc(s"${f.getName} is a directory")

      val in = Source.fromFile(f)
      val lines = in.getLines().mkString("\n")
      in.close()
      lines
    case "-i" :: cfg :: Nil => cfg
    case "-g" :: Nil        => esc(Conf.default, 0)
    case "--help" :: Nil    => esc(usage, 0)
    case _                  => esc(usage)
  }

  val cfgP = new Conf(cfgJ)

  val rt = new DumperRoutine(cfgP)
  val boot = rt.boot match {
    case Some(b) => b
    case None =>
      rt.stop()
      esc(1)
  }
}

class DumperRoutine(conf: Conf) {

  case class Boot(uid: Int, db: DB)

  implicit val sys: ActorSystem = ActorSystem()

  val cfg = conf.cfg
  val api = new Api(cfg)

  def stop(): Unit = {
    api.sttpBack.close()
    awaitU(sys.terminate())
  }

  def stopAfterBoot(b: Boot): Unit = {
    b.db.close()
    stop()
  }

  def boot: Option[Boot] = {
    // api get uid
    // open db
    ???
  }

}
