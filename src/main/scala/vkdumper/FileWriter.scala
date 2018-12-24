package vkdumper

import java.io.{File, FileOutputStream, PrintWriter}

import Utils._
import akka.actor.Actor

import scala.concurrent.Promise

sealed trait WriterMsg

case class WriteBatch(es: Iterable[String], p: Promise[Unit] = Promise()) extends WriterMsg {
  def future = p.future
}

case object CloseFile extends WriterMsg


class FileWriter(path: String, append: Boolean) extends Actor {

  val file = new File(path)
  val out = new FileOutputStream(file, append)
  val pw = new PrintWriter(out)

  var cleanFile = file.length < 2

  def writeEntry(e: String): Unit = {
    val sep = if (!cleanFile) "," else {
      cleanFile = false
      ""
    }
    pw.print(s"$sep$e")
  }

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
