package vkdumper

import org.mapdb.{DBMaker, Serializer}
import vkdumper.Utils.{CMPUtils, CachedMsgProgress}

sealed trait DBPath
case class DBFile(path: String) extends DBPath
case object DBMem extends DBPath

class DB(p: DBPath) {
  val dbm = p match {
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

  def updateProgress(peer: Int, c: CachedMsgProgress): Unit = {
    progress.put(peer, c.stringRepr)
    db.commit()
  }

  def getProgress(peer: Int): Option[CachedMsgProgress] =
    Option(progress.get(peer)).map(CMPUtils.fromString)

  def addProfiles = ??? // commit to file + update and commit profileIds

  def writeConversations = ??? // delete file + write all

  def addMessages = ??? // commit to file

}
