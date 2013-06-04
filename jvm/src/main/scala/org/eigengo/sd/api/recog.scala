package org.eigengo.sd.api

import akka.actor.{Actor, ActorRef}
import spray.http._
import spray.http.HttpResponse
import scala.util.Success
import scala.util.Failure
import org.eigengo.sd.core.Begin
import org.eigengo.sd.core.CoordinatorActor.{SingleImage, FrameChunk}

object StreamingRecogService {
  def makePattern(start: String) = (start + """(.*)""").r

  val RootUri   = "/recog"
  val MJPEGUri  = makePattern("/recog/mjpeg/")
  val H264Uri   = makePattern("/recog/h264/")
  val RtspUri   = makePattern("/recog/rtsp/")
  val StaticUri = makePattern("/recog/static/")
}

class StreamingRecogService(coordinator: ActorRef) extends Actor {
  import akka.pattern.ask
  import scala.concurrent.duration._
  import context.dispatcher
  implicit val timeout = akka.util.Timeout(2.seconds)

  import StreamingRecogService._

  def receive = {
    // begin a transaction
    case HttpRequest(HttpMethods.POST, RootUri, _, _, _) =>
      val client = sender
      (coordinator ? Begin(2)).mapTo[String].onComplete {
        case Success(sessionId) => client ! HttpResponse(entity = sessionId)
        case Failure(ex)        => client ! HttpResponse(entity = ex.getMessage, status = StatusCodes.InternalServerError)
      }

    // stream to /recog/mjpeg/:id
    case ChunkedRequestStart(HttpRequest(HttpMethods.POST, MJPEGUri(sessionId), _, entity, _)) =>
      coordinator ! SingleImage(sessionId, entity.buffer, false)
      // stream to /recog/h264/:id
    case ChunkedRequestStart(HttpRequest(HttpMethods.POST, H264Uri(sessionId), _, entity, _)) =>
      coordinator ! FrameChunk(sessionId, entity.buffer, false)
    case MessageChunk(body, extensions) =>
      // parse the body
      val frame = Array.ofDim[Byte](body.length - 38)
      Array.copy(body, 38, frame, 0, frame.length)

      val sessionId = new String(body, 0, 36)
      val marker    = body(36)
      val end       = body(37) == 'E'

      val message   = if (marker == 'H') FrameChunk(sessionId, frame, end) else SingleImage(sessionId, frame, end)
      coordinator   ! message
    case ChunkedMessageEnd(extensions, trailer) =>
      sender ! HttpResponse(entity = "{}")

    // POST to /recog/static/:id
    case HttpRequest(HttpMethods.POST, StaticUri(sessionId), _, entity, _) =>
      coordinator ! SingleImage(sessionId, entity.buffer, true)

    // POST to /recog/rtsp/:id
    case HttpRequest(HttpMethods.POST, RtspUri(sessionId), _, entity, _) =>
      println(entity.asString)
      sender ! HttpResponse(entity = "Listening to " + entity.asString)

    // all other requests
    case HttpRequest(method, uri, _, _, _) =>
      println("XXX")
      sender ! HttpResponse(entity = "No such endpoint. That's all we know.", status = StatusCodes.NotFound)
  }

}
