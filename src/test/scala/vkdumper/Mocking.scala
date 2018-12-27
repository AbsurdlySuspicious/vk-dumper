package vkdumper

import java.util.concurrent.ConcurrentLinkedQueue

import com.typesafe.scalalogging.LazyLogging
import vkdumper.ApiData._

import scala.collection.concurrent.TrieMap
import scala.util.Random
import MockingCommon._
import monix.eval.Task

object MockingCommon {
  type RespMapK = (Int, Long)
  type RespMapV = List[Task[Result[ApiConvMsgResp]]]

  case class MsgH(peer: Int, ms: List[ApiMessage])
  case class Msg(peer: Int, m: ApiMessage)
  case class Req(peer: Int, ids: List[Long])
}

case class MockingOpts(
    cfg: Cfg = Cfg(token = ""),
    msgResponses: Map[RespMapK, RespMapV] = Map.empty,
    inputProfiles: Map[(Int, Long), List[ApiUser]] = Map.empty, // K = (peer, from)
    cidFilter: Long => Boolean = _ => true
)

class ApiMock(opts: MockingOpts)
    extends Api(opts.cfg)
    with LazyLogging {

  val rnd = new Random

  val msgReqs = new ConcurrentLinkedQueue[Req]
  val convPeers = new ConcurrentLinkedQueue[Int]

  val msgResps = new TrieMap[RespMapK, RespMapV]
  msgResps ++= opts.msgResponses

  def pHolderMsg(peer: Int, cid: Long) =
    ApiMessage(1337,
               1,
               rnd.nextLong,
               0,
               peer,
               rnd.nextString(5),
               cid,
               None,
               Nil,
               Nil)

  def pHolderConv(peer: Int) = ApiConversation(ApiConvId("user", peer), None)

  override def usersGet(uid: Int*) = ???

  override def getMe = ???

  override def getMsgByConvId(peer: Int, cids: Seq[Long]) = {
    val cidHead = cids.head
    val k = peer -> cidHead
    msgResps.get(k) match {
      case Some(task :: lft) =>
        msgResps(k) = lft
        task
      case _ =>
        Task {
          msgReqs.add(Req(peer, cids.toList))
          val newCids = cids.filter(opts.cidFilter).toList
          val newPrf = opts.inputProfiles.getOrElse(peer -> cidHead, Nil)
          //logger.info(s"newPrf $peer -> $cidHead: $newPrf")
          ApiConvMsgResp(1337, newCids.map(pHolderMsg(peer, _)), newPrf)
        }.map(Res(_))
    }
  }

  override def getConversations(offset: Long, count: Int) =
    Task {
      val p = rnd.nextInt
      convPeers.add(p)
      val cList = (1 to count).map(_ =>
        ApiConvListItem(pHolderConv(p), pHolderMsg(p, rnd.nextLong)))
      ApiConvList(1337, cList.toList)
    }.map(Res(_))
}
