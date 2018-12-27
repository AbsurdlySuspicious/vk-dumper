package vkdumper

import java.io.{ByteArrayOutputStream, File, FileOutputStream, PrintWriter}

import Utils._
import akka.actor.Actor

import scala.concurrent.Promise

sealed trait WriterMsg

case class WriteBatch(es: Iterable[String], p: Promise[Unit] = Promise())
    extends WriterMsg {
  def future = p.future
}

case object CloseFile extends WriterMsg

class FileWriter(path: Option[String], append: Boolean) extends Actor {

  val (out, isCleanOnStart) = path match {
    case Some(p) =>
      val file = new File(p)
      (
        new FileOutputStream(file, append),
        file.length < 2
      )
    case None =>
      (
        new ByteArrayOutputStream(),
        true
      )
  }

  val pw = new PrintWriter(out)

  var cleanFile = isCleanOnStart

  def writeEntry(e: String): Unit = {
    val sep =
      if (!cleanFile) ","
      else {
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
