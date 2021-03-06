package vkdumper

import java.io.File
import java.time._
import java.time.format.DateTimeFormatter

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization._
import scopt.OParser
import vkdumper.ApiData.Res
import vkdumper.EC._
import vkdumper.Utils._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.io.{Codec, Source}
import scala.language.postfixOps

sealed trait Mode
//case object Noop extends Mode
case object Download extends Mode
case object GenCfg extends Mode

case class Options(
    config: File = new File("vkdumper.cfg"),
    modes: List[Mode] = Nil
)

object Const {
  val msgStep = 100
  val msgOffsetStep = 200
  val convStep = 200
  val convStartId = 2000000000
}

case class Cfg(
    fallbackAttempts: Int = 3,
    connectionTimeout: Int = 10,
    readTimeout: Int = 10,
    commonPar: Int = 4,
    throttleCount: Int = 3,
    throttlePeriod: Double = 1,
    baseDir: String = "DumpedData",
    token: String
) {

  val thrCount = throttleCount
  val thrTime = throttlePeriod.seconds

}

object Conf {

  implicit val fmt: Formats = DefaultFormats

  def default: String = {
    val fmtS: AnyRef with Formats = Serialization.formats(NoTypeHints)
    val c = Cfg(token = "")
    val s = write(c)(fmtS)
    pretty(parse(s))
  }
}

class Conf(json: String) {
  import Conf._

  private val j = parse(json)
  val cfg = j.extract[Cfg]
}

object VkDumper extends App with LazyLogging {

  implicit val codec: Codec = Codec.UTF8

  val optB = OParser.builder[Options]
  val optP = {
    import optB._
    OParser.sequence(
      programName("vkdumper"),
      head("vkdumper 0.2"),
      //
      note("modes:"),
      opt[Unit]('D', "download")
        .text("Download all conversations")
        .action((_, o) => o.copy(modes = Download :: o.modes)),
      opt[Unit]('G', "gencfg")
        .text("Generate default config to stdout")
        .action((_, o) => o.copy(modes = GenCfg :: o.modes)),
      //
      checkConfig { o =>
        val m = o.modes
        if (m.isEmpty) failure("No mode selected")
        else if (m.length > 1) failure("Only one mode should be selected")
        else success
      },
      //
      note("options:"),
      opt[File]('c', "cfg")
        .text("Config, defaults to vkdumper.cfg")
        .action((f, o) => o.copy(config = f)),
      help("help")
        .text("Print usage text"),
      //
      note("\n")
    )
  }

  val opts = OParser.parse(optP, args, Options()) match {
    case Some(o) => o
    case None    => esc(1)
  }

  def cm(m: Mode) =
    opts.modes.contains(m)

  def optfail(m: String): Unit =
    esc(s"Error: $m\nTry --help for more information.\n")

  if (cm(GenCfg))
    esc(Conf.default, 0)

  val cfgP = {
    val f = opts.config
    if (!f.exists) optfail(s"No file ${f.getName} exists")
    if (!f.isFile) optfail(s"${f.getName} is a directory")

    try {
      val in = Source.fromFile(f)
      val lines = in.getLines().mkString("\n")
      in.close()

      new Conf(lines)
    } catch {
      case e: Throwable =>
        esc(
          s"Error: can't parse config ${opts.config}. Original exception:\n\n$e")
    }
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
