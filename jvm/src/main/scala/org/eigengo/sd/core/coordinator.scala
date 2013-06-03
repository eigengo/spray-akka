package org.eigengo.sd.core

import akka.actor.{ActorRef, Props, Actor}
import java.util.UUID
import akka.routing.FromConfig

object CoordinatorActor {

  // Single ``image`` to session ``id``
  case class SingleImage(id: String, image: Array[Byte])

  // Chunk of H.264 stream to session ``id``
  case class FrameChunk(id: String, chunk: Array[Byte])

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
    case SingleImage(id, image) =>
      sessionActorFor(id).tell(RecogSessionActor.Image(image), sender)
    case FrameChunk(id, chunk) =>
      sessionActorFor(id).tell(RecogSessionActor.Frame(chunk), sender)
    case GetSessions =>
      sender ! context.children.map(_.path.name).toList
  }

  def sessionActorFor(id: String): ActorRef = context.actorFor(id)
}
