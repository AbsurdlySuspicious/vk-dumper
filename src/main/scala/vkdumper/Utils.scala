package vkdumper

import monix.execution.Scheduler

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.matching.Regex

object EC {
  implicit val genericEC: ExecutionContext = ExecutionContext.global
  implicit val genericSched: Scheduler = Scheduler.global
}

object Utils {

  val unit: Unit = ()

  object con {
    def apply(): Unit = println()
    def apply(m: Any): Unit = println(m)
    def p(m: Any): Unit = print(m)
    def np(m: Any): Unit = print(s"\n$m")
    def rp(m: Any): Unit = print(s"\r$m")

    def counter(max: Long): Long => String = {
      val ms = max.toString
      val ml = ms.length
      val f = { c: Long =>
        val cs = c.toString
        val pc = {
          val l = ml - cs.length
          if (l < 0) 0 else l
        }
        val pref = " " * pc
        s"$pref$cs/$ms"
      }; f
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

  type Rng = (Long, Long)

  def rngCheck(rng: Rng*): Unit = rng foreach {
    case (a, b) if a > b => throw new ArithmeticException("Bad range")
    case _ => ()
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
    else (l, r) match {
      case (false, false) => mergeRanges(cr, rem, hl :+ e)
      case (true, true)   => hl ::: list
      case (true, _)      => mergeRanges(ef -> ct, rem, hl)
      case (_, true)      => mergeRanges(cf -> et, rem, hl)
    }
  }


  object CMPUtils {
    val re = new Regex("""\((\d+)_(\d+)\)""")

    def fromString(str: String) = {
      def rep(s: String) = re.findAllIn(s)
      val rng = str
        .split(",")
        .filter(_.nonEmpty)
        .map(rep)
        .map { m =>
          m.group(1).toLong ->
            m.group(2).toLong
        }
        .toList
      CachedMsgProgress(rng)
    }
  }

  case class CachedMsgProgress(ranges: List[(Long, Long)]) {
    def stringRepr: String =
      ranges
        .map { case (s, e) => s"(${s}_$e)" }
        .mkString(",")
  }


}
