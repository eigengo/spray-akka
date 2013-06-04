package org.eigengo.sd

import scala.annotation.tailrec
import org.eigengo.sd.core.{ConfigCoreConfiguration, Core, Begin, CoordinatorActor}
import scala.io.Source
import java.io.{InputStream, BufferedInputStream, FileInputStream}
import scala.util.Try

object Shell extends App with Core with ConfigCoreConfiguration {

  import CoordinatorActor._
  import Commands._
  import akka.actor.ActorDSL._
  import Utils._

  implicit val _ = actor(new Act {
      become {
        case x => println(">>> " + x)
      }
    })

  @tailrec
  private def commandLoop(): Unit = {
    Console.readLine() match {
      case QuitCommand                => return
      case BeginCommand(count)        => coordinator ! Begin(count.toInt)
      case GetSessionsCommand         => coordinator ! GetSessions
      case ImageCommand(id, fileName) => coordinator ! SingleImage(id, readAll(fileName), true)
      case VideoCommand(id, fileName) => readChunks(fileName, 64)(coordinator ! FrameChunk(id, _, true))
      case GetInfoCommand(id)         => coordinator ! GetInfo(id)

      case _                          => println("WTF??!!")
    }

    commandLoop()
  }

  commandLoop()
  system.shutdown()
}

object Commands {

  val BeginCommand       = "begin:(\\d+)".r
  val GetSessionsCommand = "ls"

  val ImageCommand       = "([0-9a-z\\-]{36})/image:?(.*)".r
  val VideoCommand       = "([0-9a-z\\-]{36})/h264:?(.*)".r
  val GetInfoCommand     = "([0-9a-z\\-]{36})".r
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
      val buffer = Array.ofDim[Byte](16000)
      Thread.sleep(buffer.length / kbps)   // simulate slow input :(
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