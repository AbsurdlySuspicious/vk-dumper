package vkdumper

import java.nio.ByteBuffer

import com.softwaremill.sttp._
import com.softwaremill.sttp.asynchttpclient.monix.AsyncHttpClientMonixBackend
import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import monix.reactive.Observable
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.concurrent.duration._
import scala.language.postfixOps

class Api(cfg: Cfg) {

  import ApiData._
  import cfg._

  implicit val sttpBack: SttpBackend[Task, Observable[ByteBuffer]] =
    AsyncHttpClientMonixBackend(
      options = SttpBackendOptions.connectionTimeout(connectionTimeout seconds)
    )

  val baseParams = Map(
    "v" -> "5.92",
    "access_token" -> token
  )

  val apiPrefix = "https://api.vk.com/method"

  def get(method: String,
          args: (String, String)*): RequestT[Id, String, Nothing] =
    sttp
      .get(uri"$apiPrefix/$method?$baseParams&$args")
      .readTimeout(readTimeout seconds)

  def usersGet(uid: Int*): Task[Result[List[ApiUser]]] =
    get("users.get", "user_ids" -> uid.mkString(","), "fields" -> apiUserFields)
      .send()
      .map(apiDecode[List[ApiUser]])

  def getMe: Task[Result[List[ApiUser]]] =
    get("users.get")
      .send()
      .map(apiDecode[List[ApiUser]])

  def getMsgByConvId(peer: Int, cids: Seq[Long]): Task[Result[ApiConvMsgResp]] =
    get(
      "messages.getByConversationMessageId",
      "peer_id" -> peer.toString,
      "conversation_message_ids" -> cids.mkString(","),
      "extended" -> "1",
      "fields" -> apiConvMsgFields
    ).send()
      .map(apiDecode[ApiConvMsgResp])

  def getHistory(peer: Int,
                 offset: Int,
                 count: Int): Task[Result[ApiConvMsgResp]] =
    get(
      "messages.getHistory",
      "peer_id" -> peer.toString,
      "offset" -> offset.toString,
      "count" -> count.toString,
      "rev" -> "1",
      "extended" -> "1",
      "fields" -> apiConvMsgFields
    ).send()
      .map(apiDecode[ApiConvMsgResp])

  def getConversations(offset: Int, count: Int): Task[Result[ApiConvList]] =
    get(
      "messages.getConversations",
      "extended" -> "0",
      "offset" -> offset.toString,
      "count" -> count.toString
    ).send()
      .map(apiDecode[ApiConvList])

}
