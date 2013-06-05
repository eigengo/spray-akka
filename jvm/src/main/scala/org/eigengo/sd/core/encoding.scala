package org.eigengo.sd.core

/**
 * Contains functions that encode the image to be consumed on the other end of the AMQP
 * queue. Viz ``/native/im.cpp``.
 *
 * Also, notice that our magic constant is [Face of Boe](http://en.wikipedia.org/wiki/Face_of_Boe)
 */
trait ImageEncoding {

  private def writeBEInt32(value: Int): Array[Byte] = {
    val b0: Byte = ((value & 0xff000000) >> 24).toByte
    val b1: Byte = ((value & 0x00ff0000) >> 16).toByte
    val b2: Byte = ((value & 0x0000ff00) >> 8).toByte
    val b3: Byte =  (value & 0x000000ff).toByte

    Array(b0, b1, b2, b3)
  }

  // David Tennant was the best Doctor Who, anyway.
  private final val Magic = writeBEInt32(0xface0fb0)
  private final val One = writeBEInt32(0x00000001)

  private def singleImage(image: Array[Byte]): Array[Byte] = {
    val im: Array[Byte] = Array.ofDim(12 + image.length)
    val size = writeBEInt32(image.length)
    Array.copy(Magic, 0,    im, 0, 4)
    Array.copy(One, 0,      im, 4, 4)
    Array.copy(size, 0,     im, 8, 4)
    Array.copy(image, 0,    im, 12, image.length)

    im
  }

  @inline
  def mkImagePayload(image: Array[Byte]): Array[Byte] = singleImage(image)


}