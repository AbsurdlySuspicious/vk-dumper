package vkdumper

import scala.concurrent.duration._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization._


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
    throttlePeriod: Int = 1,
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
