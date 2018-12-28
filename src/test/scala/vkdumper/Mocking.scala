package vkdumper

import java.util.concurrent.ConcurrentLinkedQueue

import com.typesafe.scalalogging.LazyLogging
import vkdumper.ApiData._

import scala.collection.concurrent.TrieMap
import scala.util.Random
import MockingCommon._
import monix.eval.Task

import scala.collection.JavaConverters._
import Utils._
import akka.actor.ActorRefFactory

object MockingCommon {
  type RespMapK = (Int, Long)
  type RespMapV = List[Task[Result[ApiConvMsgResp]]]
  type CLQ[T] = ConcurrentLinkedQueue[T]

  def ns: Nothing = throw new UnsupportedOperationException("not mocked")
}

case class MockingOpts(
    cfg: Cfg = Cfg(token = "")
)

class ApiMock(opts: MockingOpts)
    extends Api(opts.cfg)
    with LazyLogging {

  val rnd = new Random

  case class MsgR(peer: Int, offset: Int, count: Int)
  val msgReqs = new CLQ[MsgR]

  case class ConvR(offset: Int, count: Int)
  val convReqs = new CLQ[ConvR]

  val msgResps = new TrieMap[Int, List[ApiMessage]]
  val convResps = new CLQ[ApiConvListItem]
  val userResps = new CLQ[ApiUser]

  val historyErrors = new TrieMap[(Int, Int), CLQ[Any]]
  val convErrors = new TrieMap[Int, CLQ[Any]]

  def clearAll(): Unit = {
    msgReqs.clear()
    convReqs.clear()

    msgResps.clear()
    convResps.clear()
    userResps.clear()

    historyErrors.clear()
    convErrors.clear()
  }

  private def pushC[T](c: CLQ[T], o: List[T]): Unit = {
    c.clear()
    c.addAll(o.asJava)
  }

  def pushCv(c: List[ApiConvListItem]): Unit =
    pushC(convResps, c)

  def pushUsers(u: List[ApiUser]): Unit =
    pushC(userResps, u)

  def pushMsg(peer: Int, m: List[ApiMessage]): Unit =
    msgResps.put(peer, m)

  def pushMsgErr(peer: Int, offset: Int, err: List[Any]): Unit =
    historyErrors.put(peer -> offset, new CLQ(err.asJava))

  def pushCvErr(offset: Int, err: List[Any]): Unit =
    convErrors.put(offset, new CLQ(err.asJava))

  override def get(method: String, args: (String, String)*) = ns
  override def usersGet(uid: Int*) = ns
  override def getMe = ns
  override def getMsgByConvId(peer: Int, cids: Seq[Long]) = ns

  override def getHistory(peer: Int, offset: Int, count: Int) = Task {
    val pe = Option(historyErrors.getOrElse(peer -> offset, new CLQ).poll())
    pe.getOrElse(unit) match {
      case e: ResErr => e
      case t: Throwable => throw t
      case _ =>
        val u = userResps.asScala.toList
        val rq = MsgR(peer, offset, count)
        msgReqs.add(rq)
        val rs = msgResps.get(peer) match {
          case None => ApiConvMsgResp(0, Nil, u)
          case Some(m) =>
            val storedCount = m.length
            val nl = m.slice(offset, offset + count)
            ApiConvMsgResp(storedCount, nl, u)
        }
        Res(rs)
    }
  }

  override def getConversations(offset: Int, count: Int) = Task {
    val pe = Option(convErrors.getOrElse(offset, new CLQ).poll())
    pe.getOrElse(unit) match {
      case e: ResErr => e
      case t: Throwable => throw t
      case _ =>
        val rq = ConvR(offset, count)
        convReqs.add(rq)
        val resps = convResps.asScala.toList
        val storedCount = resps.length
        val rs = resps.slice(offset, offset + count)
        Res(ApiConvList(storedCount, rs))
    }
  }

}

class DBMock(opts: MockingOpts)(implicit fac: ActorRefFactory) extends DB(InMem) {

  val usersM = new CLQ[ApiUser]
  val msgM = new CLQ[ApiMessage]

  override def addProfiles(in: Iterable[ApiUser]) = {
    usersM.addAll(in.toList.asJava)
    super.addProfiles(in)
  }

  override def addMessages(msgs: Iterable[ApiMessage]) = {
    msgM.addAll(msgs.toList.asJava)
    super.addMessages(msgs)
  }
}
