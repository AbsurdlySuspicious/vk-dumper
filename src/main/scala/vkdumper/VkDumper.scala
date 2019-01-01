package vkdumper

import java.io.{File, FileOutputStream, PrintWriter}

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging

import scala.io.{Codec, Source}
import scala.language.postfixOps
import Utils._
import EC._

import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.runtime.ScalaRunTime
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import scopt.OParser
import vkdumper.ApiData.Res

sealed trait Modes
case object Noop extends Modes
case object Download extends Modes
case object GenCfg extends Modes

case class Options(
    config: File = new File("vkdumper.cfg"),
    mode: Modes = Noop
)

object VkDumper extends App with LazyLogging {

  implicit val codec: Codec = Codec.UTF8

  val apb = OParser.builder[Options]
  val ap = {
    import apb._

    OParser.sequence(
      programName("vkdumper"),
      help("help").text("print usage"),
      opt[File]('c', "cfg")
        .valueName("<FILE>")
        .action((f, o) => o.copy(config = f))
        .text("Config, defaults to vkdumper.cfg"),
      cmd("D")
        .text("Download messages")
        .action((_, o) => o.copy(mode = Download)),
      cmd("G")
        .text("Generate default config")
        .action((_, o) => o.copy(mode = GenCfg))
    )
  }

  val opts = OParser.parse(ap, args, Options()) match {
    case Some(c) => c
    case None => esc(1)
  }

  opts.mode match {
    case Download => ()
    case GenCfg => esc(Conf.default, 0)
    case Noop => esc("No mode selected")
  }

  val cfgP = {
    val f = opts.config
    if (!f.exists) esc(s"No file ${f.getName} exists")
    if (!f.isFile) esc(s"${f.getName} is a directory")

    val in = Source.fromFile(f)
    val lines = in.getLines().mkString("\n")
    in.close()
    new Conf(lines)
  }

  val rt = new DumperRoutine(cfgP)
  val boot = rt.boot match {
    case Some(b) => b
    case None =>
      rt.stop()
      esc(1)
  }

  rt.flow(boot)
  rt.stopAfterBoot(boot)

  con("\nDone")
}

class DumperRoutine(conf: Conf) {

  case class Boot(uid: Int, db: DB, fp: FilePath)

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

  val oneReqTimeout = 60.seconds
  val longTimeout = 650.days

  def boot: Option[Boot] =
    awaitT(oneReqTimeout, api.getMe.runToFuture) match {
      case Res(me :: Nil) =>
        val userStr = s"User: [${me.id}] ${me.first_name} ${me.last_name}"
        con(userStr)
        val fp = FilePath(me.id, cfg.baseDir)
        val db = new DB(fp)
        pwHelper(fp.accLog, userStr, append = true)
        Some(Boot(me.id, db, fp))
      case e =>
        con(e.toString)
        None
    }

  def flow(boot: Boot): Unit = try {
    val flows = new DumperFlow(boot.db, api, cfg)

    val convCount = awaitT(oneReqTimeout, api.getConversations(0, 0).runToFuture) match {
      case Res(c) => c.count
      case e =>
        con(s"Failure (on convCount): $e")
        return
    }

    val convList =
      awaitT(longTimeout, flows.convFlow(convCount))
          .asScala
          .toList

    boot.fp.convLog.foreach { p =>
      implicit val formats: Formats = Serialization.formats(NoTypeHints)
      pwHelper(p, convList.map(write(_)).mkString(","), append = false)
    }

    awaitU(longTimeout, flows.msgFlow(convList))
  }
  catch {
    case e: Throwable => con(s"Failure: $e")
  }



}
