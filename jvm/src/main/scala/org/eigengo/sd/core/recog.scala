package org.eigengo.sd.core

import akka.actor._
import scala.concurrent.Future
import com.rabbitmq.client.AMQP
import com.github.sstone.amqp.{ConnectionOwner, RpcClient}
import akka.util.Timeout
import com.github.sstone.amqp.Amqp.Publish
import com.github.sstone.amqp.RpcClient.Response
import scala.Some
import com.github.sstone.amqp.RpcClient.Request
import com.github.sstone.amqp.Amqp.Delivery
import spray.json.{JsonParser, JsonReader, DefaultJsonProtocol}

object RecogSessionActor {

  // receive image to be processed
  private[core] case class Image(image: Array[Byte]) extends AnyVal
  // receive chunk of a frame to be processed
  private[core] case class Frame(frameChunk: Array[Byte]) extends AnyVal

  // information about the running session
  private[core] case object GetInfo

  // FSM states
  private[core] sealed trait State
  private[core] case object Idle extends State
  private[core] case object Completed extends State
  private[core] case object Aborted extends State
  private[core] case object Active extends State

  // FSM data
  private[core] sealed trait Data
  private[core] case object Empty extends Data
  private[core] case class Running(minCoins: Int, decoder: Option[DecoderContext]) extends Data

  // CV responses
  private[core] case class Coin(center: Double, radius: Double)
  private[core] case class CoinResponse(coins: List[Coin], succeeded: Boolean)

//  private[core] case class Face(left: Double, top: Double, width: Double, height: Double)
//  private[core] case class FacesResponse(faces: List[Face], succeeded: Boolean)
}

trait RecogSessionActorFormats extends DefaultJsonProtocol {
  import RecogSessionActor._

  implicit val CoinFormat = jsonFormat2(Coin)
  implicit val CoinResponseFormat = jsonFormat2(CoinResponse)
}

private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] with
  AmqpOperations with
  ImageEncoding with
  ActorLogging with
  RecogSessionActorFormats {

  import RecogSessionActor._
  import scala.concurrent.duration._
  import context.dispatcher

  // make a connection to the AMQP broker
  val amqp = ConnectionOwner.createChildActor(amqpConnection, Props(new RpcClient()))

  // default timeout for all states
  val stateTimeout = 60.seconds

  // our own id
  lazy val selfId: String = self.path.name

  // thank goodness for British spelling :)
  val emptyBehaviour: StateFunction = { case _ => stay() }

  def countCoins(image: Array[Byte]): Unit =
    amqpAsk[CoinResponse](amqp)("amq.direct", "count.key", mkImagePayload(image)) onSuccess {
      case res => jabberActor ! res
    }

  // we start idle and empty and then progress through the states
  startWith(Idle, Empty)

  when(Idle, stateTimeout) {
    case Event(Begin(minCoins), _) =>
      sender ! selfId
      goto(Active) using Running(minCoins, None)
  }

  when(Active, stateTimeout) {
    case Event(Image(image), r@Running(minCount, None)) if image.length > 0  =>
      val decoder = new NoopDecoderContext(countCoins)
      decoder.decode(image)
      stay() using r.copy(decoder = Some(decoder))
    case Event(Image(image), Running(minCount, Some(decoder: ImageDecoderContext))) if image.length > 0  =>
      decoder.decode(image)
      stay()
    case Event(Image(_), Running(_, Some(decoder))) =>
      decoder.close()
      goto(Completed)

    case Event(Frame(frame), r@Running(minCount, None)) =>
      val decoder = new H264DecoderContext(countCoins)
      decoder.decode(frame)
      stay() using r.copy(decoder = Some(decoder))
    case Event(Frame(frame), Running(minCount, Some(decoder: VideoDecoderContext))) if frame.length > 0 =>
      decoder.decode(frame)
      stay()
    case Event(Frame(_), Running(_, Some(decoder))) =>
      decoder.close()
      goto(Completed)
  }

  // until we hit Aborted and Completed, which do nothing interesting
  when(Aborted)(emptyBehaviour)
  when(Completed)(emptyBehaviour)

  // unhandled events in the states
  whenUnhandled {
    case Event(StateTimeout, _) => goto(Aborted)
    case Event(GetInfo, _)      => sender ! "OK"; stay()
  }

  // cleanup
  onTransition {
    case _ -> Aborted =>
      println("Aborting!")
      context.stop(self)
    case _ -> Completed =>
      println("Done!")
      context.stop(self)
  }

  // go!
  initialize

  // cleanup
  override def postStop() {
    context.stop(amqp)
  }
}

private[core] trait AmqpOperations {
  this: Actor =>

  protected def amqpAsk[A : JsonReader](amqp: ActorRef)
                       (exchange: String, routingKey: String, payload: Array[Byte]): Future[A] = {
    import scala.concurrent.duration._
    import akka.pattern.ask
    import context.dispatcher
    val reader = implicitly[JsonReader[A]]

    val builder = new AMQP.BasicProperties.Builder
    implicit val timeout = Timeout(2.seconds)

    (amqp ? Request(Publish(exchange, routingKey, payload, Some(builder.build())) :: Nil)).map {
      case Response(Delivery(_, _, _, body)::_) =>
        val s = new String(body)
        reader.read(JsonParser(s))
      case x => sys.error("Bad match " + x)
    }
  }

}
