package vkdumper

import com.softwaremill.sttp.Response
import com.typesafe.scalalogging.LazyLogging
import org.json4s._
import org.json4s.jackson.JsonMethods._

object ApiErrors {
  val tooManyRequests = 6
  val tooManyActions = 9
  val authorizationFailed = 5
  val noPermissions = 7
  val captchaNeeded = 14
}

object ApiData extends LazyLogging {

  implicit val jsonFormats: Formats = DefaultFormats
  import DefaultReaders._

  type EarlyResp = Response[String]
  //type Resp[T] = Response[Either[DeserializationError[circe.Error], T]]

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

  def apiDecode[T: Manifest]: EarlyResp => Result[T] = { in =>
    (in.body, in.code) match {
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
  val apiConvMsgIdStep = 100
  case class ApiConvMsgResp(
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

  case class ApiConvListItem(
      conversation: ApiConversation,
      last_message: ApiMessage
  ) extends ApiObject

  val apiConvListOffsetStep = 200
  case class ApiConvList(
      count: Int,
      items: List[ApiConvListItem]
  ) extends ApiObject

}
