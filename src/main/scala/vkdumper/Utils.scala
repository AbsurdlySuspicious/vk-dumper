package vkdumper

import java.io.{FileOutputStream, PrintWriter}

import monix.execution.Scheduler
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.{DefaultReaders, Formats, NoTypeHints}
import vkdumper.Utils._

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, Awaitable}
import scala.runtime.ScalaRunTime

object EC {
  //implicit val genericEC: ExecutionContext = ExecutionContext.global
  //  - Monix scheduler provides EC
  implicit val genericSched: Scheduler = Scheduler.global
}

class ProgressPrinter {
  def conv(curr: Int, total: Int): Unit = {
    val c = con.counter(total, curr + 1)
    con(s"[$c/$total] updating conversations...")
  }

  def convDone(total: Int): Unit =
    con(s"[$total/$total] conversation update done")

  private def msgStartText(peer: Int, c: String) =
    s"[$c   0%] conversation $peer"

  def msgStart(peer: Int, pos: ConvPos): Unit =
    con(msgStartText(peer, pos.cs))

  def msgStart(peer: Int, n: Int, total: Int): Unit = {
    val c = con.counter(total, n + 1)
    con(msgStartText(peer, s"$c/$total"))
  }

  def msg(peer: Int, offset: Int, pos: ConvPos): Unit = {
    val t = pos.total
    val p = con.counter(100, Math.round(100D / t * offset).toInt)
    val m = con.counter(t, offset + 1)
    con(s"[${pos.cs} $p%] msg $m/$t, peer $peer")
  }

  def msgDone(peer: Int, pos: ConvPos): Unit = {
    val t = pos.total
    con(s"[${pos.cs} 100%] msg $t/$t, peer $peer")
  }
}

object Utils {

  val unit: Unit = ()

  object prog extends ProgressPrinter

  object con {
    def apply(): Unit = println()
    def apply(m: Any): Unit = println(m)
    def p(m: Any): Unit = print(m)
    def np(m: Any): Unit = print(s"\n$m")
    def rp(m: Any): Unit = print(s"\r$m")

    def counter(max: Int, c: Int): String = {
      val ms = max.toString
      val ml = ms.length

      val cs = c.toString
      val pc = {
        val l = ml - cs.length
        if (l < 0) 0 else l
      }
      val pref = " " * pc
      s"$pref$cs"
    }
  }

  @tailrec
  def foldList[T](src: List[T], acc: T)(f: (T, T) => Option[T]): List[T] = {
    if (src.isEmpty) return acc :: Nil
    f(acc, src.head) match {
      case Some(a) => foldList(src.tail, a)(f)
      case None    => acc :: src
    }
  }

  type Rng = (Int, Int)

  def rngCheck(rng: Rng*): Unit = rng foreach {
    case (a, b) if a > b => throw new ArithmeticException("Bad range")
    case _               => ()
  }

  @tailrec
  def mergeRanges(cr: Rng, list: List[Rng], hl: List[Rng] = Nil): List[Rng] = {
    if (list.isEmpty) return (cr :: hl).sortBy(_._1)

    val (cf, ct) = cr
    val (e @ (ef, et)) :: rem = list

    val l = cf <= (et + 1) && cf >= ef
    val r = (ct + 1) >= ef && ct <= et
    val o = ef > cf && et < ct

    rngCheck(e, cr)

    if (o) mergeRanges(cr, rem, hl)
    else
      (l, r) match {
        case (false, false) => mergeRanges(cr, rem, hl :+ e)
        case (true, true)   => hl ::: list
        case (true, _)      => mergeRanges(ef -> ct, rem, hl)
        case (_, true)      => mergeRanges(cf -> et, rem, hl)
      }
  }

  def await[T](a: Awaitable[T]): T = awaitT(60.seconds, a)
  def awaitT[T](time: FiniteDuration, a: Awaitable[T]): T =
    Await.result(a, time)
  def awaitU(as: Awaitable[Any]*): Unit = as.foreach(await)
  def awaitU(time: FiniteDuration, as: Awaitable[Any]*): Unit =
    as.foreach(awaitT(time, _))

  def esc(m: String, code: Int = 1): Nothing = {
    println(m)
    System.exit(code)
    throw new Exception("trap")
  }

  def esc(code: Int): Nothing =
    esc("", code)

  def pwHelper(file: String, content: String, append: Boolean): Unit = {
    val o = new FileOutputStream(file, append)
    val pw = new PrintWriter(o)
    pw.println(content)
    pw.flush()
    pw.close()
  }

  trait ProductToString { this: Product =>
    override def toString = ScalaRunTime._toString(this)
  }

  object CMPUtils {
    implicit val formats: Formats = Serialization.formats(NoTypeHints)
    import DefaultReaders._

    def fromString(str: String) = {
      val j = parse(str)

      CachedMsgProgress(
        (j \ "r").extract[List[List[Int]]].collect {
          case f :: t :: Nil => f -> t
        },
        (j \ "last").as[Int]
      )
    }
  }

  case class CachedMsgProgress(ranges: List[Rng], lastMsgId: Int) {
    import CMPUtils._

    def stringRepr: String = {
      val j = (
        "r" -> ranges.map { case (f, t) => List(f, t) }
      ) ~ ("last" -> lastMsgId)

      compact(render(j))
    }

    private def makeOffset(rng: List[Rng]): Int = rng match {
      case Nil         => 0
      case (_, o) :: _ => o
    }

    def lastOffset: Int = makeOffset(ranges.reverse)
    def leastOffset: Int = makeOffset(ranges)

  }

}
