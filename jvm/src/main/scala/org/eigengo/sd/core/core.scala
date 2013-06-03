package org.eigengo.sd.core

import akka.actor.{Props, ActorSystem}
import com.rabbitmq.client.ConnectionFactory
import com.github.sstone.amqp.ConnectionOwner

case class Begin(minCoins: Int)

trait Core {
  // start the actor system
  implicit val system = ActorSystem("recog")

  // connection factory
  private val connectionFactory = new ConnectionFactory(); connectionFactory.setHost("localhost")

  // create a "connection owner" actor, which will try and reconnect automatically if the connection ins lost
  val amqpConnection = system.actorOf(Props(new ConnectionOwner(connectionFactory)))

  // create the coordinator actor
  val coordinator = system.actorOf(Props(new CoordinatorActor(amqpConnection)), "coordinator")

}
