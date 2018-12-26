package vkdumper

import java.net.{
  ConnectException,
  NoRouteToHostException,
  SocketException,
  UnknownHostException
}
import java.util.concurrent.{
  ConcurrentLinkedQueue,
  ForkJoinPool,
  TimeoutException
}
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
import scala.concurrent._
import scala.language.postfixOps
import scala.util.Try
import Utils._
import monix.eval.Task
import Const._
import EC.genericSched

import scala.collection.JavaConverters._
import scala.collection.immutable.{IndexedSeq, Iterable}
import scala.collection.{SeqView, immutable}

class DumperFlow(db: DB, api: ApiOperator, cfg: Cfg)(implicit sys: ActorSystem)
    extends LazyLogging {

  val svDecider: Supervision.Decider = {
    case _: ResErrWrp => Supervision.restart
    case _            => Supervision.stop
  }

  implicit val mat: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(sys).withSupervisionStrategy(svDecider)
  )

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

  def apiFMap[T]: Result[T] => Task[T] = {
    case e: ResErr => Task.raiseError(ResErrWrp(e))
    case Res(x)    => Task.now(x)
  }

  type ConvFT = ConcurrentLinkedQueue[ApiConvListItem]

  def convFlow(count: Int): Future[ConvFT] = {
    val (step, thrCount, thrTime) = (
      Const.convStep,
      cfg.thrCount,
      cfg.thrTime
    )

    val q = new ConvFT
    val pr = Promise[ConvFT]()

    val stream = Stream
      .iterate(0)(_ + step)
      .takeWhile(_ < count)

    val sink = Sink.onComplete { _ =>
      prog.convDone(count)
      pr.success(q)
    }

    Source(stream)
      .throttle(thrCount, thrTime)
      .backpressureTimeout(bpDelay)
      .mapAsync(1) { o =>
        prog.conv(o, count)
        api
          .getConversations(o, step)
          .flatMap(apiFMap)
          .onErrorRestartLoop(mRetry)(retryFun)
          .runToFuture
      }
      .map(a => q.addAll(a.items.asJavaCollection))
      .runWith(sink)

    // todo
    //  check last_message / chat msg count inclusion

    pr.future
  }

  case class ConvPreMap(peer: Int, last: Int, convN: Int, convT: Int) {
    def conv(startOffset: Int, totalCount: Int) =
      Conv(peer, startOffset, totalCount, last, convN -> convT)

    def progress: Option[CachedMsgProgress] =
      db.getProgress(peer)
  }

  case class ChunkResp(peer: Int, lastMsg: Int, pos: ConvPos)

  // todo leastOffset

  def msgFlow(list: List[ApiConvListItem]): Future[Unit] = {

    val parApi = 3
    val inLn = list.length
    val input: Iterable[ConvPreMap] =
      list.view.zipWithIndex
        .map {
          case (ApiConvListItem(c, m), cn) =>
            ConvPreMap(c.peer.id, m.id, cn, inLn)
        }
        .to[Iterable]

    val prm = Promise[Unit]()
    val sink = Sink.onComplete(_ => prm.success(unit))

    Source(input)
      .map(a => a -> a.progress)
      .filter {
        case (_, None) => true
        case (pm, pr) =>
          pr.exists(p => p.ranges.length <= 1 && p.lastMsgId != pm.last)
      }
      .mapAsync(1) {
        case (pm, pr) =>
          prog.msgStart(pm.peer, pm.convN, pm.convT)
          api
            .getHistory(pm.peer, 0, 0)
            .flatMap(apiFMap)
            .onErrorRestartLoop(mRetry)(retryFun)
            .map(_.count)
            .map(r => (pm, pr, r))
            .runToFuture
      }
      .map {
        case (pm, None, c)     => pm.conv(0, c)
        case (pm, Some(pr), c) => pm.conv(pr.lastOffset, c)
      }
      .mapAsync(1) { c =>
        Source(c.stream)
          .mapAsync(parApi) {
            case ch @ Chunk(peer, offset, count, pos) =>
              prog.msg(peer, offset, pos)
              api
              .getHistory(peer, offset, count)
              .flatMap(apiFMap)
              .onErrorRestartLoop(mRetry)(retryFun)
              .map(r => (r, ch))
              .runToFuture
          }
          .filter(_._1.items.nonEmpty)
          .map {
            case (r, Chunk(peer, offset, count, pos)) =>
              val last = r.items.last.id
              db.updateProgress(peer) { opt =>
                val old = opt.map(_.ranges).getOrElse(Nil)
                val nr = mergeRanges(offset -> (offset + count), old)
                CachedMsgProgress(nr, last)
              }
              ChunkResp(peer, last, pos)
          }
          .runWith(Sink.last)
          .map {
            case ChunkResp(peer, _, pos) => prog.msgDone(peer, pos)
          }
       }
      .runWith(sink)

    prm.future
  }

}
