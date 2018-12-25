package vkdumper

import java.io.File

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging

import scala.io.{Codec, Source}
import scala.language.postfixOps
import Utils._

import scala.concurrent.duration._
import scala.runtime.ScalaRunTime

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
}

object Exp extends App with LazyLogging {

  case class Foo(a: Int) extends Exception with ProductToString
  case class Bar(a: Int) extends Throwable with ProductToString

  println(s"foo: ${Foo(1337)}")
  println(s"bar: ${Bar(1337)}")

  implicit val sys: ActorSystem = ActorSystem()

  val flow = new DumperFlow(null, null, null)

  val f = flow.testFlow
  awaitU(60.seconds, f)

  sys.terminate()

}
