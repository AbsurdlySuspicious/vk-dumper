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

object ApiErrors {
  val tooManyRequests = 6
  val serverError = 10
  val tooManyActions = 9
  val authorizationFailed = 5
  val noPermissions = 7
  val captchaNeeded = 14
}

object ApiData extends LazyLogging {

  implicit val jsonFormats: Formats = DefaultFormats
  import DefaultReaders._

  type EarlyResp = Response[String]

  case class MalformedJson(srcJson: String)
      extends Exception(s"Malformed json: $srcJson")

  sealed trait ApiObject

  sealed trait Result[+T] {
    def map[R](f: T => R): Result[R]
  }

  trait ResErr extends Result[Nothing] {
    override def map[R](f: Nothing => R): this.type = this
  }

  case class Res[T](data: T) extends Result[T] {
    override def map[R](f: T => R) = Res(f(data))
  }

  case class ApiError(
      error_code: Int,
      error_msg: String
  ) extends ResErr

  case class HttpError(
      code: Int
  ) extends ResErr

  case class ResErrWrp(f: ResErr) extends Exception

  def apiDecode[T: Manifest]: EarlyResp => Result[T] =
    in => apiDecodeStr(in.body, in.code)

  def apiDecodeStr[T: Manifest](body: Either[String, String], htCode: Int): Result[T] = {
    (body, htCode) match {
      case (Left(_), code) => HttpError(code)
      case (Right(raw), _) =>
        val body = parse(raw)
        val err = (body \ "error").toOption.map { e =>
          ApiError(
            (e \ "error_code").as[String].toInt,
            (e \ "error_msg").as[String]
          )
        }
        val res = (body \ "response").extractOpt[T]
        (err, res) match {
          case (Some(e), _) => e
          case (_, Some(r)) => Res(r)
          case _            => throw MalformedJson(raw)
        }
    }

  }

  val apiUserFields = "photo_100"
  case class ApiUser(
      first_name: String,
      last_name: String,
      id: Int,
      photo_100: Option[String]
  ) extends ApiObject

  case class ApiChatAction(`type`: String, member_id: Int) extends ApiObject

  case class ApiMessage(
      date: Long,
      from_id: Int,
      id: Int,
      out: Int,
      peer_id: Int,
      text: String,
      action: Option[ApiChatAction],
      attachments: List[JObject],
      fwd_messages: List[JObject]
  ) extends ApiObject {
    def isOut: Boolean = out == 1
  }

  val apiConvMsgFields = "first_name,last_name,photo_100"
  case class ApiMessagesResponse(
      count: Int,
      items: List[ApiMessage],
      profiles: List[ApiUser]
  ) extends ApiObject

  case class ApiConvId(
      `type`: String, // user, chat, group, email
      id: Int
  ) extends ApiObject

  case class ApiChatSettings(
      title: String,
      members_count: Int,
      state: String, // in, kicked, left
      photo: Option[JObject]
  ) extends ApiObject

  case class ApiConversation(
      peer: ApiConvId,
      chat_settings: Option[ApiChatSettings]
  ) extends ApiObject

  case class ApiMessageItem(
      conversation: ApiConversation,
      last_message: ApiMessage
  ) extends ApiObject

  val apiConvListOffsetStep = 200
  case class ApiConvList(
      count: Int,
      items: List[ApiMessageItem]
  ) extends ApiObject

}

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

  def getMsgByConvId(peer: Int, cids: Seq[Long]): Task[Result[ApiMessagesResponse]] =
    get(
      "messages.getByConversationMessageId",
      "peer_id" -> peer.toString,
      "conversation_message_ids" -> cids.mkString(","),
      "extended" -> "1",
      "fields" -> apiConvMsgFields
    ).send()
      .map(apiDecode[ApiMessagesResponse])

  def getHistory(peer: Int,
                 offset: Int,
                 count: Int,
                 rev: Boolean): Task[Result[ApiMessagesResponse]] =
    get(
      "messages.getHistory",
      "peer_id" -> peer.toString,
      "offset" -> offset.toString,
      "count" -> count.toString,
      "rev" -> (if (rev) "1" else "0"),
      "extended" -> "1",
      "fields" -> apiConvMsgFields
    ).send()
      .map(apiDecode[ApiMessagesResponse])

  def getConversations(offset: Int, count: Int): Task[Result[ApiConvList]] =
    get(
      "messages.getConversations",
      "extended" -> "0",
      "offset" -> offset.toString,
      "count" -> count.toString
    ).send()
      .map(apiDecode[ApiConvList])

}
