package org.eigengo.sd

import scala.annotation.tailrec
import org.eigengo.sd.core.{Core, Begin, CoordinatorActor}
import scala.io.Source
import java.io.{InputStream, BufferedInputStream, FileInputStream}
import scala.util.Try

object Shell extends App with Core {

  import CoordinatorActor._
  import Commands._
  import akka.actor.ActorDSL._
  import Utils._

  val printliner = actor(new Act {
    become {
      case x => println(">>> " + x)
    }
  })

  @tailrec
  private def commandLoop(): Unit = {
    implicit val sender = printliner

    Console.readLine() match {
      case QuitCommand =>
        system.shutdown()

      case BeginCommand(count) =>
        coordinator ! Begin(count.toInt)
        commandLoop()
      case GetSessionsCommand =>
         coordinator ! GetSessions
         commandLoop()

      case ImageCommand(id, fileName) =>
        val data = readAll(fileName)
        coordinator ! SingleImage(id, data)
        commandLoop()
      case VideoCommand(id, fileName) =>
        readChunks(fileName, 128)(coordinator ! FrameChunk(id, _))
        commandLoop()
      case GetInfoCommand(id) =>
        coordinator ! GetInfo(id)
        commandLoop()

      case _ =>
        println("??!!")
        commandLoop()
    }
  }

  commandLoop()
}

object Commands {

  val BeginCommand       = "begin:(\\d+)".r
  val GetSessionsCommand = "ls"

  val ImageCommand       = "([0-9a-z\\-]*)/image:?(.*)".r
  val VideoCommand       = "([0-9a-z\\-]*)/video:?(.*)".r
  val GetInfoCommand     = "([0-9a-z\\-]*)".r
  val QuitCommand        = "quit"

}

object Utils {
  private def getFullFileName(fileName: String) = {
    getClass.getResource(fileName).getPath
  }


  // Chuck Norris deals with all exceptions
  def readAll(fileName: String): Array[Byte] = {
    val is = new BufferedInputStream(new FileInputStream(getFullFileName(fileName)))
    val contents = Stream.continually(is.read).takeWhile(-1 !=).map(_.toByte).toArray
    is.close()
    contents
  }

  def readChunks[U](fileName: String, kbps: Int)(f: Array[Byte] => U): Unit = {

    @tailrec
    def read(is: InputStream): Unit = {
      val buffer = Array.ofDim[Byte](65535)
      Thread.sleep(buffer.length / kbps)   // simluate slow input
      val len = is.read(buffer)
      if (len > 0) {
        f(buffer)
        read(is)
      } else {
        f(Array.ofDim(0))
      }
    }

    val is = new BufferedInputStream(new FileInputStream(getFullFileName(fileName)))
    read(is)
    is.close()
  }

}