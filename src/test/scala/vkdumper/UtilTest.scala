package vkdumper

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{FlatSpec, Matchers}

import scala.language.implicitConversions

class UtilTest extends FlatSpec with Matchers with LazyLogging {

  import vkdumper.Utils.{mergeRanges, Rng}

  implicit def tl(n: (Int, Int)): (Long, Long) =
    n match { case (x, y) => x.toLong -> y.toLong }

  "mergeRanges" should "merge fucking ranges" in {

    val l1 = List[Rng](42 -> 130, 140 -> 250)

    // left
    mergeRanges(42 -> 131, l1) shouldBe List(42 -> 131, 140 -> 250)
    mergeRanges(250 -> 280, l1) shouldBe List(42 -> 130, 140 -> 280)
    mergeRanges(251 -> 300, l1) shouldBe List(42 -> 130, 140 -> 300)
    mergeRanges(230 -> 300, l1) shouldBe List(42 -> 130, 140 -> 300)
    mergeRanges(270 -> 300, l1) shouldBe List(42 -> 130, 140 -> 250, 270 -> 300)

    // right
    mergeRanges(41 -> 130, l1) shouldBe List(41 -> 130, 140 -> 250)
    mergeRanges(15 -> 42, l1) shouldBe List(15 -> 130, 140 -> 250)
    mergeRanges(15 -> 41, l1) shouldBe List(15 -> 130, 140 -> 250)
    mergeRanges(15 -> 55, l1) shouldBe List(15 -> 130, 140 -> 250)
    mergeRanges(15 -> 30, l1) shouldBe List(15 -> 30, 42 -> 130, 140 -> 250)

    // inside
    mergeRanges(150 -> 160, l1) shouldBe l1
    mergeRanges(42 -> 130, l1) shouldBe l1

    // outside
    mergeRanges(30 -> 135, l1) shouldBe List(30 -> 135, 140 -> 250)
    mergeRanges(139 -> 600, l1) shouldBe List(42 -> 130, 139 -> 600)
    mergeRanges(41 -> 131, l1) shouldBe List(41 -> 131, 140 -> 250)

    // gap filling
    val filled = List(42 -> 250)
    mergeRanges(120 -> 150, l1) shouldBe filled
    mergeRanges(129 -> 141, l1) shouldBe filled
    mergeRanges(130 -> 140, l1) shouldBe filled
    mergeRanges(131 -> 140, l1) shouldBe filled

    // single ranges
    mergeRanges(135 -> 135, l1) shouldBe List(42 -> 130, 135 -> 135, 140 -> 250)
    mergeRanges(130 -> 130, l1) shouldBe l1
    mergeRanges(140 -> 140, l1) shouldBe l1
    mergeRanges(131 -> 131, l1) shouldBe List(42 -> 131, 140 -> 250)
    mergeRanges(139 -> 139, l1) shouldBe List(42 -> 130, 139 -> 250)
    mergeRanges(55 -> 55, List(40 -> 54, 56 -> 80)) shouldBe List(40 -> 80)

    // empty input
    mergeRanges(5 -> 10, Nil) shouldBe List(5 -> 10)

  }

  it should "merge larger range lists" in {

    val l2 = List[Rng](30 -> 45, 50 -> 60, 80 -> 101)

    // gap filling
    mergeRanges(40 -> 55, l2) shouldBe List(30 -> 60, 80 -> 101)
    mergeRanges(46 -> 49, l2) shouldBe List(30 -> 60, 80 -> 101)

    // outside gap filling
    mergeRanges(40 -> 85, l2) shouldBe List(30 -> 101)
    mergeRanges(46 -> 79, l2) shouldBe List(30 -> 101)
  }

  it should "throw on bad ranges" in {

    val goodList = List[Rng](1 -> 5, 10 -> 15)
    val badList = List[Rng](8 -> 7)

    val goodRng = 30 -> 40
    val badRng = 40 -> 30

    mergeRanges(goodRng, goodList) shouldBe (goodList :+ goodRng)

    type Ex = ArithmeticException

    assertThrows[Ex](mergeRanges(badRng, badList))
    assertThrows[Ex](mergeRanges(badRng, goodList))
    assertThrows[Ex](mergeRanges(goodRng, badList))
  }

  import Utils.{CachedMsgProgress, CMPUtils}

  "CachedMsgProgress" should "serialize and deserialize" in {

    def cm(p: (Int, Int)*) = CachedMsgProgress(p.toList)

    //val ts = CMPUtils.fromString("foobar")
    //println("ts: " + ts)

    val input = List(
      cm((10, 15),
        (5, 8),
        (15, 18)),
      cm((1, 5), (6, 7)),
      cm(),
      cm((1337, 3000))
    )

    input.foreach { cmp =>
      val s = cmp.stringRepr
      println(s"$cmp: $s")
      cmp shouldBe CMPUtils.fromString(s)
    }

  }
}
