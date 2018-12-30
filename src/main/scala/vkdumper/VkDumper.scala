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
import scopt.OParser

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
