package vkdumper

import java.net.{
  ConnectException,
  NoRouteToHostException,
  SocketException,
  UnknownHostException
}
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

import ApiData._
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

class Flows(db: DB, api: ApiOperator)(implicit sys: ActorSystem) {

  implicit val mat: ActorMaterializer = ActorMaterializer()

  def convFlow = {



  }

}
