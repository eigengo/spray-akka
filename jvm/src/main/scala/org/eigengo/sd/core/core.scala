package org.eigengo.sd.core

import akka.actor.{ Props, ActorSystem }
import com.rabbitmq.client.ConnectionFactory
import com.github.sstone.amqp.ConnectionOwner

case class Begin(minCoins: Int)

/**
 * Contains configuration for the core
 */
trait CoreConfiguration {

  def amqpConnectionFactory: ConnectionFactory

}

/**
 * Implementation of the ``CoreConfiguration`` that uses the underlying ``system``'s ``settings``.
 */
trait ConfigCoreConfiguration extends CoreConfiguration {
  def system: ActorSystem

  private val amqpHost = system.settings.config.getString("spray-akka.amqp.host")
  // connection factory
  val amqpConnectionFactory = new ConnectionFactory(); amqpConnectionFactory.setHost(amqpHost)

}

/**
 * Contains the functionality of the "headless" part of our app
 */
trait Core {
  this: CoreConfiguration =>

  // start the actor system
  implicit val system = ActorSystem("recog")

  // create a "connection owner" actor, which will try and reconnect automatically if the connection ins lost
  val amqpConnection = system.actorOf(Props(new ConnectionOwner(amqpConnectionFactory)))

  // create the coordinator actor
  val coordinator = system.actorOf(Props(new CoordinatorActor(amqpConnection)), "coordinator")

}
