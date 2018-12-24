package vkdumper

import java.io.{File, FileOutputStream, PrintWriter}

import akka.actor.{ActorRefFactory, Props}
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.mapdb.{DBMaker, Serializer}
import vkdumper.ApiData.{ApiConversation, ApiMessage, ApiUser}
import vkdumper.Utils.{CMPUtils, CachedMsgProgress}
import EC._
import monix.eval.Task

import scala.concurrent.Future


sealed trait DBPathOpt
case object DBDefault extends DBPathOpt

sealed trait DBPath extends DBPathOpt
case class DBFile(path: String) extends DBPath
case object DBMem extends DBPath

case class FilePath(uid: Int, baseDir: String, private val overrideDb: DBPathOpt = DBDefault) {

  private def root(p: String) = s"$baseDir/$uid/$p"

  val baseDirFile = new File(baseDir)
  val uidRootFile = new File(root(""))

  val dbPath: DBPath = overrideDb match {
    case DBDefault => DBFile(root(s"dumper.db"))
    case x: DBPath => x
  }

  val profileLog =
    root(s"users_$uid.csv")

  val convLog =
    root(s"clist_$uid.csv")

  val msgLog =
    root(s"msg_$uid.csv")

  def bootstrap(): Unit = {
    baseDirFile.mkdir()
    uidRootFile.mkdir()
  }

}

class DB(fp: FilePath)(implicit fac: ActorRefFactory) {

  implicit val formats: Formats = Serialization.formats(NoTypeHints)

  fp.bootstrap()

  val dbm = fp.dbPath match {
    case DBFile(path) => DBMaker.fileDB(path)
    case DBMem        => DBMaker.memoryDB()
  }

  val db = dbm
    .fileMmapEnableIfSupported()
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

  val msgFile = new FileWriterWrapper(fp.msgLog)
  val userFile = new FileWriterWrapper(fp.profileLog)

  def updateProgress(peer: Int, c: CachedMsgProgress): Unit = {
    progress.put(peer, c.stringRepr)
    db.commit()
  }

  def getProgress(peer: Int): Option[CachedMsgProgress] =
    Option(progress.get(peer)).map(CMPUtils.fromString)

  def addProfiles(in: Iterator[ApiUser]): Future[Unit] = {
    val json = in.map(write(_)).toIterable
    val ids = in.map(_.id)
    def add(id: Int) = profileIds.add(id)
    userFile
      .writeAll(json)
      .map(_ => ids.foreach(add))
  }

  def writeConversations(in: Iterator[ApiConversation]): Future[Unit] = {
    val fw = new FileWriterWrapper(fp.convLog, append = false)
    val json = in.map(write(_)).toIterable
    fw.writeAll(json)
      .map(_ => fw.close())
  }

  def addMessages(msgs: Iterator[ApiMessage]): Future[Unit] = {
    val json = msgs.map(write(_)).toIterable
    msgFile.writeAll(json)
  }

  def close(): Unit = {
    db.close()
    msgFile.close()
    userFile.close()
  }

}

class FileWriterWrapper(path: String, append: Boolean = true)(implicit fac: ActorRefFactory) {

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
