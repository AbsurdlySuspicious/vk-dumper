package vkdumper

import Const._
import vkdumper.ApiData.ApiConversation
import vkdumper.Utils.con

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.collection.immutable.Iterable

case class ConvPos(total: Int, convN: Int, convCount: Int) {
  val counter = con.counter(convCount, convN + 1)
  val cs = s"$counter/$convCount"
}

sealed trait WorkInput

case object TerminateFlow extends WorkInput

case class Chunk(peer: Int, offset: Int, count: Int) extends WorkInput

case class Conv(peer: Int, startOffset: Int, totalCount: Int, lastMsgId: Int, convNC: (Int, Int)) {

  val (convN, convCount) = convNC
  val pos = ConvPos(totalCount, convN, convCount)

  def stream: Stream[WorkInput] = {
    val step = Const.msgOffsetStep

    val s = Stream
      .iterate(startOffset)(_ + step)
      .takeWhile(_ < totalCount)
      .map(o => Chunk(peer, o, step))

    s ++ List(TerminateFlow)
  }

}
