package vkdumper

import java.io.{ByteArrayOutputStream, File, FileOutputStream, PrintWriter}

import akka.actor.{Actor, ActorRefFactory, Props}
import com.typesafe.scalalogging.LazyLogging
import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization._
import org.mapdb.{DBMaker, Serializer}
import vkdumper.ApiData.{ApiConversation, ApiMessage, ApiUser}
import vkdumper.EC._
import vkdumper.Utils.{CMPUtils, CachedMsgProgress, unit}

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}

sealed trait DBPathOpt
case object DBDefault extends DBPathOpt

sealed trait DBPath extends DBPathOpt
case class DBFile(path: String) extends DBPath
case object DBMem extends DBPath

sealed trait DBCfg {
  def bootstrap(): Unit
  val dbPath: DBPath
  val profileLog: Option[String]
  val convLog: Option[String]
  val msgLog: Option[String]
}

case class FilePath(uid: Int,
                    baseDir: String,
                    private val overrideDb: DBPathOpt = DBDefault)
    extends DBCfg {

  private def root(p: String) = s"$baseDir/$uid/$p"

  val baseDirFile = new File(baseDir)
  val uidRootFile = new File(root(""))

  val dbPath: DBPath = overrideDb match {
    case DBDefault => DBFile(root(s"dumper.db"))
    case x: DBPath => x
  }

  val profileLog =
    Some(root(s"users_$uid.csv"))

  val convLog =
    Some(root(s"clist_$uid.csv"))

  val msgLog =
    Some(root(s"msg_$uid.csv"))

  val accLog =
    s"$baseDir/dumped_users.log"

  def bootstrap(): Unit = {
    baseDirFile.mkdir()
    uidRootFile.mkdir()
  }

}

case object InMem extends DBCfg {
  override def bootstrap(): Unit = {}

  override val dbPath = DBMem
  override val profileLog = None
  override val convLog = None
  override val msgLog = None
}

class DB(val fp: DBCfg)(implicit fac: ActorRefFactory) extends LazyLogging {

  implicit val formats: Formats = Serialization.formats(NoTypeHints)

  fp.bootstrap()

  val dbm = fp.dbPath match {
    case DBFile(path) => DBMaker.fileDB(path).fileMmapEnableIfSupported()
    case DBMem        => DBMaker.memoryDB()
  }

  val db = dbm
    .transactionEnable()
    .closeOnJvmShutdown()
    .make()

  val profileIds = db
    .treeSet("profile")
    .serializer(Serializer.INTEGER)
    .counterEnable()
    .createOrOpen()

  val progress = db
    .treeMap("progress")
    .keySerializer(Serializer.INTEGER)
    .valueSerializer(Serializer.STRING)
    .createOrOpen()
    .asScala

  val msgFile = new FileWriterWrapper(fp.msgLog)
  val userFile = new FileWriterWrapper(fp.profileLog)

  def setProgress(peer: Int, c: CachedMsgProgress): Unit = {
    progress.put(peer, c.stringRepr)
    db.commit()
  }

  def getProgress(peer: Int): Option[CachedMsgProgress] =
    progress.get(peer).map(CMPUtils.fromString)

  def updateProgress(peer: Int)(
      f: Option[CachedMsgProgress] => CachedMsgProgress): Unit = {
    val c = getProgress(peer)
    setProgress(peer, f(c))
  }

  def hasProfile(peer: Int): Boolean =
    profileIds.contains(peer)

  def addProfiles(in: Iterable[ApiUser]): Future[Unit] =
    Future(in.filter(u => !hasProfile(u.id)).toList)
      .flatMap { lst =>
        val ft = lst.view
        val json = ft.map(write(_))
        val ids = ft.map(_.id)
        userFile
          .writeAll(json)
          .map { _ =>
            ids.foreach(profileIds.add(_))
            db.commit()
          }
      }

  def writeConversations(in: Iterable[ApiConversation]): Future[Unit] = {
    val fw = new FileWriterWrapper(fp.convLog, append = false)
    val json = in.view.map(write(_))
    fw.writeAll(json)
      .map(_ => fw.close())
  }

  def addMessages(msgs: Iterable[ApiMessage]): Future[Unit] = {
    val json = msgs.view.map(write(_))
    msgFile.writeAll(json)
  }

  def close(): Unit = {
    db.close()
    msgFile.close()
    userFile.close()
  }

}

class FileWriterWrapper(path: Option[String], append: Boolean = true)(
    implicit fac: ActorRefFactory) {

  val props = Props(new FileWriter(path, append))
  val ref = fac.actorOf(props)

  def writeAll(es: Iterable[String]): Future[Unit] = {
    val msg = WriteBatch(es)
    ref ! msg
    msg.future
  }

  def close(): Unit = {
    ref ! CloseFile
  }

}

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
