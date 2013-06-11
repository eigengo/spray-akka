package org.eigengo.sd.core

import com.xuggle.xuggler._
import javax.imageio.ImageIO
import java.io.{ FileOutputStream, File, FileInputStream, ByteArrayOutputStream }
import java.nio.channels.ReadableByteChannel
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import scala.collection.mutable.ArrayBuffer

/**
 * Decoder context can decode a chunk (either starting | intermediate xor end); if
 * appropriate, a decoder may also be closeable
 */
trait DecoderContext {
  def decode(chunk: Array[Byte], end: Boolean)
  def close()
}

/**
 * More specialized subtypes of the DecoderContext separate out the video | still decoders
 */
trait VideoDecoderContext extends DecoderContext
trait ImageDecoderContext extends DecoderContext

/**
 * Chunks the incoming chunks (what?) until it receives the _end_ chunk; then
 * it applies the _sum_ of all previous chunks to ``f``; resets and waits for more
 * chunks.
 *
 * It is not to be shared by multiple threads.
 *
 * @param f the function to be applied on complete frame
 * @tparam U return type
 */
case class ChunkingDecoderContext[U](f: Array[Byte] => U) extends ImageDecoderContext {
  val buffer: ArrayBuffer[Byte] = ArrayBuffer()

  def decode(chunk: Array[Byte], end: Boolean) {
    if (buffer.isEmpty && end) {
      f(chunk)
    } else {
      buffer ++= chunk
      if (end) {
        f(buffer.toArray)
        buffer clear ()
      }
    }
  }

  def close(): Unit = {}
}

/**
 * Decodes the incoming chunks of H.264 stream; applies the function ``f`` to all
 * completed frames in the stream.
 *
 * It is not to be shared by multiple threads.
 *
 * @param f the function to be applied to every decoded frame
 * @tparam U return type
 */
class H264DecoderContext[U](f: Array[Byte] => U) extends VideoDecoderContext {
  val container = IContainer.make()
  val tf = new TemporaryFile

  var isOpen = false
  var videoStream: IStream = _
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

  def decode(chunk: Array[Byte], end: Boolean): Unit = {
    tf.write(chunk)

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

  def close(): Unit = {
    tf.close()
    if (videoCoder != null) videoCoder.close()
  }

}

/**
 * Ghetto!
 */
private[core] class TemporaryFile /* extends UtterlyMiserable */ {
  val file: File = File.createTempFile("video", "mp4")
  //val file: File = new File("/Users/janmachacek/x.mp4")
  file.deleteOnExit()
  var open: Boolean = true
  private val fos: FileOutputStream = new FileOutputStream(file)

  def write(buffer: Array[Byte]): Unit = {
    if (open) {
      fos.write(buffer)
      fos.flush()
    }
  }

  def close(): Unit = {
    if (open) {
      fos.close()
      file.delete()
      open = false
    }
  }

}
