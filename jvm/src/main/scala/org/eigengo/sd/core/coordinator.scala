package org.eigengo.sd.core

import akka.actor.{ActorRef, Props, Actor}
import java.util.UUID
import akka.routing.FromConfig

object CoordinatorActor {

  // Single ``image`` to session ``id``
  case class SingleImage(id: String, image: Array[Byte], end: Boolean)

  // Chunk of H.264 stream to session ``id``
  case class FrameChunk(id: String, chunk: Array[Byte], end: Boolean)

  // list ids of all sessions
  case object GetSessions

  // get information about a session ``id``
  case class GetInfo(id: String)
}

class CoordinatorActor(amqpConnection: ActorRef) extends Actor {
  import CoordinatorActor._

  val jabber = context.actorOf(Props[JabberActor].withRouter(FromConfig()), "jabber")

  def receive = {
    case b@Begin(_) =>
      val rsa = context.actorOf(Props(new RecogSessionActor(amqpConnection, jabber)), UUID.randomUUID().toString)
      rsa.forward(b)
    case GetInfo(id) =>
      sessionActorFor(id).tell(RecogSessionActor.GetInfo, sender)
    case SingleImage(id, image, start) =>
      sessionActorFor(id).tell(RecogSessionActor.Image(image, start), sender)
    case FrameChunk(id, chunk, start) =>
      sessionActorFor(id).tell(RecogSessionActor.Frame(chunk, start), sender)
    case GetSessions =>
      sender ! context.children.map(_.path.name).toList
  }

  def sessionActorFor(id: String): ActorRef = context.actorFor(id)
}
