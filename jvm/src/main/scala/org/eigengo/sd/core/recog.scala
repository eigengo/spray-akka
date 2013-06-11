package org.eigengo.sd.core

import akka.actor._
import scala.concurrent.{ ExecutionContext, Future }
import com.rabbitmq.client.AMQP
import com.github.sstone.amqp.{ ConnectionOwner, RpcClient }
import akka.util.Timeout
import com.github.sstone.amqp.Amqp.Publish
import com.github.sstone.amqp.RpcClient.Response
import scala.Some
import com.github.sstone.amqp.RpcClient.Request
import com.github.sstone.amqp.Amqp.Delivery
import spray.json.{ JsonParser, JsonReader, DefaultJsonProtocol }

private[core] object RecogSessionActor {

  // receive image to be processed
  private[core] case class Image(image: Array[Byte], end: Boolean)
  // receive chunk of a frame to be processed
  private[core] case class Frame(frameChunk: Array[Byte], end: Boolean)

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

/**
 * We define the instances of ``JsonReader`` for various types in a trait, because
 * we may decide to use them outside of the core; I can imagine a situation where
 * we would like to use them in our REST API.
 *
 * In that case, all that we'd have to do is to mix in this trait to our Spray endpoint
 * and then happily use ``complete`` or ``ctx.complete``.
 */
trait RecogSessionActorFormats extends DefaultJsonProtocol {
  import RecogSessionActor._

  implicit val CoinFormat = jsonFormat2(Coin)
  implicit val CoinResponseFormat = jsonFormat2(CoinResponse)
}

/**
 * This actor deals with the states of the recognition session. We use FSM here--even though
 * we only have a handful of states, recognising things sometimes needs many more states
 * and using ``become`` and ``unbecome`` of the ``akka.actor.ActorDSL._`` would be cumbersome.
 *
 * @param amqpConnection the AMQP connection we will use to create individual 'channels'
 * @param jabberActor the actor that will receive our output
 */
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with FSM[RecogSessionActor.State, RecogSessionActor.Data] with AmqpOperations with ImageEncoding with ActorLogging with RecogSessionActorFormats {

  import RecogSessionActor._
  import scala.concurrent.duration._
  import context.dispatcher

  // default timeout for all states
  val stateTimeout = 60.seconds

  // thank goodness for British spelling :)
  val emptyBehaviour: StateFunction = { case _ => stay() }

  // make a connection to the AMQP broker
  val amqp = ConnectionOwner.createChildActor(amqpConnection, Props(new RpcClient()))

  // we start idle and empty and then progress through the states
  startWith(Idle, Empty)

  // when we receive the ``Begin`` even when idle, we become ``Active``
  when(Idle, stateTimeout) {
    case Event(Begin(minCoins), _) =>
      sender ! self.path.name
      goto(Active) using Running(minCoins, None)
  }

  // when ``Active``, we can process images and frames
  when(Active, stateTimeout) {
    case Event(Image(image, end), r @ Running(minCoins, None)) =>
      // Image with no decoder yet. We will be needing the ChunkingDecoderContext.
      val decoder = new ChunkingDecoderContext(countCoins(minCoins))
      decoder.decode(image, end)
      stay() using r.copy(decoder = Some(decoder))
    case Event(Image(image, end), Running(minCount, Some(decoder: ImageDecoderContext))) if image.length > 0 =>
      // Image with existing decoder. Shut up and apply.
      decoder.decode(image, end)
      stay()
    case Event(Image(_, _), Running(_, Some(decoder))) =>
      // Empty image (following the previous case)
      decoder.close()
      goto(Completed)

    case Event(Frame(frame, _), r @ Running(minCoins, None)) =>
      // Frame with no decoder yet. We will be needing the H264DecoderContext.
      val decoder = new H264DecoderContext(countCoins(minCoins))
      decoder.decode(frame, true)
      stay() using r.copy(decoder = Some(decoder))
    case Event(Frame(frame, _), Running(minCount, Some(decoder: VideoDecoderContext))) if frame.length > 0 =>
      // Frame with existing decoder. Just decode. (Teehee--I said ``Just``.)
      decoder.decode(frame, true)
      stay()
    case Event(Frame(_, _), Running(_, Some(decoder))) =>
      // Last frame
      decoder.close()
      goto(Completed)
  }

  // until we hit Aborted and Completed, which do nothing interesting
  when(Aborted)(emptyBehaviour)
  when(Completed)(emptyBehaviour)

  // unhandled events in the states
  whenUnhandled {
    case Event(StateTimeout, _) => goto(Aborted)
    case Event(GetInfo, _) => sender ! "OK"; stay()
  }

  // cleanup
  onTransition {
    case _ -> Aborted => log.info("Aborting!"); context.stop(self)
    case _ -> Completed => log.info("Done!"); context.stop(self)
  }

  // go!
  initialize

  // cleanup
  override def postStop() {
    context.stop(amqp)
  }

  // Curried function that--when applied to the first parameter list--
  // is nicely suitable for the various ``DecoderContext``s
  def countCoins(minCoins: Int)(image: Array[Byte]): Unit =
    amqpAsk[CoinResponse](amqp)("amq.direct", "count.key", mkImagePayload(image)) onSuccess {
      case res => if (res.coins.size >= minCoins) jabberActor ! res
    }
}

/**
 * Contains useful functions (actually just one here) for the AMQP business.
 * Deals with JSON serialization.
 */
private[core] trait AmqpOperations {

  /**
   * Asks the ``amqp`` actor to reply to the message containing ``payload`` sent to the
   * ``exchange`` and ``routingKey``.
   *
   * Expects implicitly available ``ExecutionContext`` and ``JsonReader[A]``: the
   * ``ExecutionContext`` is usually available when used in an ``Actor``; the ``JsonReader[A]``
   * is available when there is a suitable instance of ``JsonReader`` for the type ``A``.
   *
   * @param amqp the AMQP actor
   * @param exchange the exchange (``amq.direct`` or such like)
   * @param routingKey the routing key (you should have defined bindings to some queue)
   * @param payload the payload for the message
   * @param ctx the execution context
   * @param reader the ``JsonReader[A]`` instance
   * @tparam A the expected response
   * @return the ``Future[A]`` of the response
   */
  def amqpAsk[A](amqp: ActorRef)(exchange: String, routingKey: String, payload: Array[Byte])(implicit ctx: ExecutionContext, reader: JsonReader[A]): Future[A] = {
    import scala.concurrent.duration._
    import akka.pattern.ask

    implicit val timeout = Timeout(2.seconds)

    (amqp ? Request(Publish(exchange, routingKey, payload) :: Nil)).map {
      case Response(Delivery(_, _, _, body) :: _) =>
        val s = new String(body)
        reader.read(JsonParser(s))
      case x => sys.error("Bad match " + x)
    }
  }

}
