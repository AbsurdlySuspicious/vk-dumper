package vkdumper

import java.io.{FileOutputStream, PrintWriter}

import Utils._
import akka.actor.Actor

import scala.concurrent.Promise

sealed trait WriterMsg

case class WriteBatch(es: Iterable[String], p: Promise[Unit] = Promise()) extends WriterMsg {
  def future = p.future
}

case object CloseFile extends WriterMsg


class FileWriter(path: String, append: Boolean) extends Actor {

  val out = new FileOutputStream(path, append)
  val pw = new PrintWriter(out)

  def writeEntry(e: String): Unit =
    pw.print(s"$e,")

  def receive = {
    case WriteBatch(es, p) =>
      es.foreach(writeEntry)
      pw.flush()
      p.success(unit)

    case CloseFile =>
      pw.flush()
      pw.close()
  }
}
