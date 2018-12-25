package vkdumper

import java.net.{
  ConnectException,
  NoRouteToHostException,
  SocketException,
  UnknownHostException
}
import java.util.concurrent.{ConcurrentLinkedQueue, TimeoutException}
import java.util.concurrent.atomic.AtomicReference

import ApiData._
import ApiErrors._
import akka.{Done, NotUsed}
import akka.actor.{ActorRef, ActorSystem}
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Scheduler

import scala.collection.immutable.Iterable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.language.postfixOps
import scala.util.Try
import Utils._
import monix.eval.Task
import Const._
import EC.genericSched

import scala.collection.JavaConverters._
import scala.collection.immutable.IndexedSeq
import scala.collection.immutable

class DumperFlow(db: DB, api: ApiOperator, cfg: Cfg)(implicit sys: ActorSystem)
    extends LazyLogging {

  implicit val mat: ActorMaterializer = ActorMaterializer()

  val mRetry = 3
  val mDelay = 5.seconds
  val bpDelay = 120.seconds

  def retryFun[B]: (Throwable, Int, Int => Task[B]) => Task[B] = {
    (t, mr, retryTask) =>
      def retry =
        retryTask(mr - 1).delayExecution(mDelay)

      def abandon =
        Task.raiseError(t)

      def raise = {
        logger.error(s"Retries exceeded ($mRetry), throwing error")
        Task.raiseError(t)
      }

      def conn(f: Exception) = {
        logger.error(s"Connection failure: $f")
        retry
      }

      if (mr <= 0) raise
      else
        t match {
          case fail: SocketException      => conn(fail)
          case fail: UnknownHostException => conn(fail)
          case ResErrWrp(err) =>
            err match {
              case HttpError(code) =>
                logger.error(s"Http error: $code")
                retry
              case ApiError(`tooManyRequests`, _) =>
                logger.error("Too many requests, pausing stream")
                retry
              case ApiError(code, m) =>
                logger.error(s"Api error $code: $m")
                abandon
            }
          case e: Exception =>
            logger.error(s"Unknown error: ${e.getMessage}")
            retry
          case fail =>
            logger.error(s"Unknown error: $fail")
            retry
        }

  }

  type ConvFT = ConcurrentLinkedQueue[ApiConversation]

  def convFlow(count: Int): Future[ConvFT] = {
    val (step, thrCount, thrTime) = (
      Const.convStep,
      cfg.thrCount,
      cfg.thrTime
    )

    val q = new ConcurrentLinkedQueue[ApiConversation]

    val pr = Promise[ConvFT]()

    val stream = Stream
      .iterate(0)(_ + step)
      .takeWhile(_ < count)

    val sink = Sink.onComplete(_ => pr.success(q))

    Source(stream)
      .throttle(thrCount, thrTime)
      .backpressureTimeout(bpDelay)
      .mapAsync(1) { o =>
        api
          .getConversations(o, step)
          .flatMap {
            case e: ResErr => Task.raiseError(ResErrWrp(e))
            case Res(x)    => Task.now(x)
          }
          .onErrorRestartLoop(mRetry)(retryFun)
          .runToFuture
      }
      .map(a => q.addAll(a.items.map(_.conversation).asJavaCollection))
      .runWith(sink)

    // todo
    //  check last_message / chat msg count inclusion
    //  progress calls

    pr.future
  }

}
