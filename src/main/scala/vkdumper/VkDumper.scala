package vkdumper

import java.io.{File, FileOutputStream, PrintWriter}

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging

import scala.io.{Codec, Source}
import java.time._
import java.time.format.DateTimeFormatter

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
import scopt.{OParser, OptionParser}
import vkdumper.ApiData.Res

sealed trait Mode
//case object Noop extends Mode
case object Download extends Mode
case object GenCfg extends Mode

case class Options(
    config: File = new File("vkdumper.cfg"),
    modes: List[Mode] = Nil
)

object VkDumper extends App with LazyLogging {

  implicit val codec: Codec = Codec.UTF8

  val optsParser: OptionParser[Options] =
    new scopt.OptionParser[Options]("vkdumper") {

      head("vkdumper 0.2")
      note("modes:")

      opt[Unit]('D', "download")
        .text("Download all conversations")
        .action((_, o) => o.copy(modes = Download :: o.modes))

      opt[Unit]('G', "gencfg")
        .text("Generate default config to stdout")
        .action((_, o) => o.copy(modes = GenCfg :: o.modes))

      checkConfig { o =>
        val m = o.modes
        if (m.isEmpty) failure("No mode selected")
        else if (m.length > 1) failure("Only one mode should be selected")
        else success
      }

      note("options:")

      opt[File]('c', "cfg")
        .text("Config, defaults to vkdumper.cfg")
        .required()
        .withFallback(() => new File("vkdumper.cfg"))
        .validate { f =>
          if (!f.exists) failure(s"No file ${f.getName} exists")
          else if (!f.isFile) failure(s"${f.getName} is a directory")
          else success
        }
        .action((f, o) => o.copy(config = f))

      help("help")
        .text("Print usage text")

      note("\n")
    }

  val opts = optsParser.parse(args, Options()) match {
    case Some(o) => o
    case None    => esc(1)
  }
  def cm(m: Mode) =
    opts.modes.contains(m)

  if (cm(GenCfg))
    esc(Conf.default, 0)

  val cfgP = {
    val f = opts.config
    val in = Source.fromFile(f)
    val lines = in.getLines().mkString("\n")
    in.close()
    new Conf(lines)
  }

  if (cm(Download)) {
    val rt = new DumperRoutine(cfgP)
    val boot = rt.boot match {
      case Some(b) => b
      case None =>
        rt.stop()
        esc(1)
    }

    rt.flow(boot)
    rt.stopAfterBoot(boot)
  }

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
        val date = LocalDateTime.now.format(
          DateTimeFormatter.ofPattern("uuuu.MM.dd HH:mm"))
        val userStr = s"[${me.id}] ${me.first_name} ${me.last_name}"
        con(s"User: $userStr")
        val fp = FilePath(me.id, cfg.baseDir)
        val db = new DB(fp)
        pwHelper(fp.accLog, s"$date: $userStr", append = true)
        Some(Boot(me.id, db, fp))
      case e =>
        con(e.toString)
        None
    }

  def flow(boot: Boot): Unit =
    try {
      val flows = new DumperFlow(boot.db, api, cfg)

      val convCount =
        awaitT(oneReqTimeout, api.getConversations(0, 0).runToFuture) match {
          case Res(c) => c.count
          case e =>
            con(s"Failure (on convCount): $e")
            return
        }

      val convList =
        awaitT(longTimeout, flows.convFlow(convCount)).asScala.toList

      boot.fp.convLog.foreach { p =>
        implicit val formats: Formats = Serialization.formats(NoTypeHints)
        pwHelper(p, convList.map(write(_)).mkString(","), append = false)
      }

      awaitU(longTimeout, flows.msgFlow(convList))
    } catch {
      case e: Throwable => con(s"Failure: $e")
    }

}
