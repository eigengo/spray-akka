package org.eigengo.sd.core

import com.xuggle.xuggler._
import javax.imageio.ImageIO
import java.io.{FileOutputStream, File, FileInputStream, ByteArrayOutputStream}
import java.nio.channels.ReadableByteChannel
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore

sealed trait DecoderContextType
case object VideoDecoderContextType extends DecoderContextType
case object ImageDecoderContextType extends DecoderContextType

trait DecoderContext {
  def decode(chunk: Array[Byte])
  def close()
}
trait VideoDecoderContext extends DecoderContext
trait ImageDecoderContext extends DecoderContext

case class NoopDecoderContext[U](f: Array[Byte] => U) extends ImageDecoderContext {
  def decode(chunk: Array[Byte]) { f(chunk) }

  def close(): Unit = {}
}

class H264DecoderContext[U](f: Array[Byte] => U) extends VideoDecoderContext {
  val container = IContainer.make()
  val tf = new TemporaryFile

  var isOpen = false
  var videoStream: IStream     = _
  var videoCoder: IStreamCoder = _

  def open(): Boolean = {
    if (!isOpen) {
      try {
        container.open(tf.file.getAbsolutePath, IContainer.Type.READ, null)
        videoStream = container.getStream(0)
        videoCoder = videoStream.getStreamCoder

        videoCoder.open(IMetaData.make(), IMetaData.make())
        isOpen = true
      } catch {
        case x: Throwable => // noop
      }
    }
    isOpen
  }

  def decode(chunk: Array[Byte]): Unit = {
    tf.write(chunk)

    tf.run {
      if (!open()) return

      val packet = IPacket.make()
      while (container.readNextPacket(packet) >= 0) {
        val picture = IVideoPicture.make(videoCoder.getPixelType, videoCoder.getWidth, videoCoder.getHeight)
        packet.getSize
        var offset = 0
        while (offset < packet.getSize) {
          val bytesDecoded = videoCoder.decodeVideo(picture, packet, offset)
          if (bytesDecoded > 0) {
            offset = offset + bytesDecoded
            if (picture.isComplete) {
              val javaImage = Utils.videoPictureToImage(picture)
              val os = new ByteArrayOutputStream
              ImageIO.write(javaImage, "png", os)
              os.close()
              f(os.toByteArray)
            }
          }
        }
      }
    }
  }

  def close(): Unit = {
    tf.close()
    if (videoCoder != null) videoCoder.close()
  }

}

class TemporaryFile {
  //val file: File = File.createTempFile("video", "mp4")
  val file: File = new File("/Users/janmachacek/x.mp4")
  var open: Boolean = true
  private val fos: FileOutputStream = new FileOutputStream(file)

  def run[U](f: => U): U = {
    val u = f

    u
  }

  def write(buffer: Array[Byte]): Unit = {
    if (open) {
      fos.write(buffer)
      fos.flush()
    }
  }

  def close(): Unit = {
    if (open) {
      fos.close()
      open = false
    }
  }

}
