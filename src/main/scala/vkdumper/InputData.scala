package vkdumper

import Const._
import vkdumper.ApiData.ApiConversation
import vkdumper.Utils.con

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.collection.immutable.Iterable

case class ConvProgress(last: Long, convN: Long, convCount: Long) {
  val counter = con.counter(convCount)(convN + 1)
}


sealed trait WorkInput

sealed trait InnerInput

sealed trait SvcInput extends InnerInput
case object TerminateFlow extends SvcInput

sealed trait InnerWorkInput extends InnerInput {
  def id: Long
}

case class ConvFromApi(conv: Conversation, fromApi: ApiConversation) extends WorkInput

trait Conversation {
  def stream: Iterable[InnerInput]
}

case class ChatChunk(peer: Int, ids: Stream[Long]) extends InnerWorkInput{
  def id = from
  def from = ids.head
  def count = ids.length
}

case class UserChunk(peer: Int, offset: Int, count: Int) extends InnerWorkInput {
  def id = offset
}

case class ConvChat(peer: Int, cidFrom: Long, cidTo: Long, prg: ConvProgress) extends Conversation {
  override def stream = {
    val takeBy = msgStep

    val stream = Stream
      .iterate(cidFrom)(_ + 1)
      .takeWhile(_ <= cidTo)
      .grouped(takeBy)
      .map(g => ChatChunk(peer, g))
      .to[Iterable]

    stream ++ List(TerminateFlow)
  }
}


case class ConvUser(peer: Int, offset: Int, msgCount: Int, prg: ConvProgress) extends Conversation {
  override def stream = {
    val takeBy = msgOffsetStep

    val stream = Stream
      .iterate(offset)(_ + takeBy)
      .takeWhile(_ < msgCount)
      .map { chunkOffset =>
        val delta = msgCount - chunkOffset
        val count = if (delta < takeBy) delta else takeBy
        UserChunk(peer, chunkOffset, count)
      }

    stream ++ List(TerminateFlow)
  }
}
