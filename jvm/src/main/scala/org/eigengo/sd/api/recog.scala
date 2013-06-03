package org.eigengo.sd.api

import akka.actor.{Actor, ActorRef}
import spray.http._
import spray.http.HttpResponse
import scala.util.Success
import scala.util.Failure
import org.eigengo.sd.core.Begin
import org.eigengo.sd.core.CoordinatorActor.{SingleImage, FrameChunk}

class StreamingRecogService(coordinator: ActorRef) extends Actor {
  import akka.pattern.ask
  import scala.concurrent.duration._
  import context.dispatcher
  implicit val timeout = akka.util.Timeout(2.seconds)

  def receive = {
    // begin a transaction
    case HttpRequest(HttpMethods.POST, "/recog", _, _, _) =>
      val client = sender
      (coordinator ? Begin(2)).mapTo[String].onComplete {
        case Success(sessionId) => client ! HttpResponse(entity = sessionId)
        case Failure(ex)        => client ! HttpResponse(entity = ex.getMessage, status = StatusCodes.InternalServerError)
      }

    // stream to /recog/stream/:id
    case ChunkedRequestStart(HttpRequest(HttpMethods.POST, uri, _, entity, _)) if uri startsWith "/recog/stream/" =>
      val sessionId = uri.substring(14)
      coordinator ! FrameChunk(sessionId, entity.buffer)
    case MessageChunk(body, extensions) =>
      // parse the body
      val frame = Array.ofDim[Byte](body.length - 36)
      Array.copy(body, 36, frame, 0, frame.length)
      val sessionId = new String(body, 0, 36)
      coordinator ! FrameChunk(sessionId, frame)
    case ChunkedMessageEnd(extensions, trailer) =>
      sender ! HttpResponse(entity = "{}")

    // POST to /recog/static/:id
    case HttpRequest(HttpMethods.POST, uri, _, entity, _) if uri startsWith "/recog/static/" =>
      val sessionId = uri.substring(12)
      coordinator ! SingleImage(sessionId, entity.buffer)

    // POST to /recog/rtsp/:id
    case HttpRequest(HttpMethods.POST, uri, _, entity, _) if uri startsWith "/recog/rtsp/" =>
      val sessionId = uri.substring(12)
      println(entity.asString)
      sender ! HttpResponse(entity = "Listening to " + entity.asString)

    // all other requests
    case HttpRequest(method, uri, _, _, _) =>
      println("XXX")
      sender ! HttpResponse(entity = "No such endpoint. That's all we know.", status = StatusCodes.NotFound)
  }

}
