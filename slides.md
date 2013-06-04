#Akka in heterogeneous environments

It'll be a show.

#Counting coins

We have a video with coins on a table. We want to process the video, count the coins, and send IM on change of the count. 

In summary, we are building this system:

```
H.264 -- (HTTP) --> Scala/Akka -- (AMQP) --> C++/CUDA 
                        |                       |
                        +<------- (AMQP) -------+
                        |
                        +------- (Jabber) ------> 
```

The main components:

* Scala & Akka processing core (Maintain multiple recognition sessions, deal with integration points.)
* Spray for the REST API (Chunked HTTP posts to receive the video.)
* AMQP connecting the native components (Array[Byte] out; JSON in.)
* C++ / CUDA for the image processing (OpenCV with CUDA build. Coin counting logic.)
* iOS client that sends the H.264 stream (Make chunked POST request to the Spray endpoint.)

#What our code does
We support multiple recognition sessions; each session maintains its own state. The session _coordinator_ starts new sessions and routes request to existing sessions.

```
            [ Begin(params) ]
??? ----->  [ SingleImage(id, ...) ]  -----> [[ coordinator ]]
            [ FrameChunk(id, ...) ]                 |
                                               +----+----+
                                               |         | 
                                       ...  [[ S ]]   [[ S ]]  ...
                                               |
                                               |
                                            [[ A ]]
```

The coordinator receives the ``Begin`` message and creates a _session actor_, passing it the _jabber actor_; the _session actor_ in turn creates connection to RabbitMQ. On receiving the ``Begin`` message, the _session actor_ replies to the sender with the id of the session.

The _???_ component is responsible for initiating these messages; it is either a command-line app or it is a complete REST server app.

#Let's begin
Let's take a look at the shell application. It extends ``App`` and presents trivial user interface.

```scala
object Shell extends App {
  import CoordinatorActor._
  import Commands._
  import akka.actor.ActorDSL._
  import Utils._
    
  @tailrec
  def commandLoop(): Unit = {
    Console.readLine() match {
      case QuitCommand => return
      case _           => println("WTF??!!")
    }
    commandLoop()
  }

  commandLoop()
}
```

So far, not so good. We can run the ``Shell`` app, which starts the command loop. The command loop reads from the console. When we type ``quit``, it exits; otherwise, it prints ``WTF??!!``. We need to get it to do something interesting.

#Layers
To allow us to assemble the shells, we structure our code into two main traits: ``Core`` and ``Api``.

```scala
trait Core {
  // start the actor system
  implicit val system = ActorSystem("recog")

  val amqpConnectionFactory: ConnectionFactory = new ConnectionFactory()
  amqpConnectionFactory.setHost("localhost")

  val amqpConnection = system.actorOf(Props(new ConnectionOwner(amqpConnectionFactory)))
  val coordinator = system.actorOf(Props(new CoordinatorActor(amqpConnection)), "coordinator")
}
```

```scala
trait Api {
  this: Core =>

  system.actorOf(Props(new HttpServer(...)), "http-server") ! HttpServer.Bind("0.0.0.0", 8080)
}
```

The code in the ``Core`` trait assembles the "headless" part of our application. It starts the ``ActorSystem`` and creates the _coordinator_ and _AMQP_ actors.

> groll next

#Core configuration
We don't want to hard-code things like the AMQP server host in our application. These need to be somehow externalised. But we may want to use different configuration for our tests; and different configuration in each test for that matter. So, we create

```scala
trait CoreConfiguration {

  def amqpConnectionFactory: ConnectionFactory

}
```

We give implementation of this trait that requires the ``system: ActorSystem`` to be available; and uses the Typesafe config to load the settings.

```scala
trait ConfigCoreConfiguration extends CoreConfiguration {
  def system: ActorSystem

  private val amqpHost = system.settings.config.getString("spray-akka.amqp.host")
  // connection factory
  val amqpConnectionFactory = new ConnectionFactory(); amqpConnectionFactory.setHost(amqpHost)

}
```

Finally, we require that ``Core`` mixes in the ``CoreConfiguration`` trait:

```scala
trait Core {
  this: CoreConfiguration =>

  ...
}
```

> groll next

#Shell II
So, let's go back to the ``Shell`` ``App`` and mix in the traits that contian the headless parts of our application; and add the necessary call to shutdown the ``ActorSystem``.

```scala
object Shell extends App with Core with ConfigCoreConfiguration {

   ...

   commandLoop()
   system.shutdown()
}
```

> groll next

#Beginning a session
The coordinator is responsible for the sessions; begins one when it receives the ``Begin`` message. The session itself lives in its own actor: a child of the coordinator:

```scala
class CoordinatorActor(amqpConnection: ActorRef) extends Actor {
  import CoordinatorActor._

  val jabber = context.actorOf(Props[JabberActor].withRouter(FromConfig()), "jabber")

  def receive = {
    case b@Begin(_) =>
      val rsa = context.actorOf(Props(new RecogSessionActor(amqpConnection, jabber)), UUID.randomUUID().toString)
      rsa.forward(b)
      ...
  }
}
```

When we receive the ``Begin`` message, the coordinator creates a new child actor for the session and forwards it the same message.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] with

  import RecogSessionActor._
  import scala.concurrent.duration._
  import context.dispatcher

  // default timeout for all states
  val stateTimeout = 60.seconds

  // we start idle and empty and then progress through the states
  startWith(Idle, Empty)

  when(Idle, stateTimeout) {
    case Event(Begin(minCoins), _) =>
      sender ! selfId
      goto(Active) using Running(minCoins, None)
  }

  initialize

}
```

This is the important stuff. The ``CoordinatorActor`` creates a new ``RecogSessionActor``; forwards it the received ``Begin`` message. The newly created ``RecogSessionActor`` replies with its ``name`` to the original sender.

> groll next

#Shell III
We will now need to add the begin command to the shell.

```scala
object Shell extends App with Core with ConfigCoreConfiguration {

  import CoordinatorActor._
  import Commands._
  import akka.actor.ActorDSL._
  import Utils._

  @tailrec
  private def commandLoop(): Unit = {
    Console.readLine() match {
      case QuitCommand                => return
      case BeginCommand(count)        => coordinator ! Begin(count.toInt)

      case _                          => println("WTF??!!")
    }

    commandLoop()
  }

  commandLoop()
  system.shutdown()
}
```

_Does anyone know how to display the response?_ We can use the _ask_ pattern, or we can simply create an actor in ``commandLoop`` to be used as the sender of all messages.

```scala
object Shell extends App with Core with ConfigCoreConfiguration {

  import CoordinatorActor._
  import Commands._
  import akka.actor.ActorDSL._
  import Utils._

  implicit val _ = actor(new Act {
      become {
        case x => println(">>> " + x)
      }
    })

  @tailrec
  def commandLoop(): Unit = {
    ...
  }
}
```

Now the ``!`` function can find the implicit ``ActorRef`` to be used as the sender. And hence, when we now type ``begin:1``, we will see the newly created session id.

> groll next

#Listing the sessions
Let's handle the command to list the sessions; in other words, when we send the coordinator the ``GetSessions`` command, we expect to get a list of ids of the active sessions.

```scala
object Shell extends App with Core with ConfigCoreConfiguration {

  import CoordinatorActor._
  import Commands._
  import akka.actor.ActorDSL._
  import Utils._

  ...

  @tailrec
  def commandLoop(): Unit = {
    ...
    case GetSessionsCommand         => coordinator ! GetSessions
    ...
  }
}
```

_What now? Any hints?_

We want to get all children, skipping the ``jabber`` actor; and then map them to just their names. Finally, we convert the iterable to ``List``.

```scala
class CoordinatorActor(amqpConnection: ActorRef) extends Actor {
  import CoordinatorActor._

  val jabber = context.actorOf(Props[JabberActor].withRouter(FromConfig()), "jabber")

  def receive = {
    case b@Begin(_) =>
      val rsa = context.actorOf(Props(new RecogSessionActor(amqpConnection, jabber)), UUID.randomUUID().toString)
      rsa.forward(b)
    case GetSessions =>
      sender ! context.children.filter(jabber !=).map(_.path.name).toList
  }

}
```

(How cool is ``jabber !=``, eh?)

> groll next

#Remaining commands
OK, let's complete the remaining commands in the Shell.

```scala
object Shell extends App with Core with ConfigCoreConfiguration {
  ...
  @tailrec
  private def commandLoop(): Unit = {
    Console.readLine() match {
      case QuitCommand                => return
      case BeginCommand(count)        => coordinator ! Begin(count.toInt)
      case GetSessionsCommand         => coordinator ! GetSessions
      case ImageCommand(id, fileName) => coordinator ! SingleImage(id, readAll(fileName), true)
      case VideoCommand(id, fileName) => readChunks(fileName, 64)(coordinator ! FrameChunk(id, _, true))
      case GetInfoCommand(id)         => coordinator ! GetInfo(id)

      case _                          => println("WTF??!!")
    }
    commandLoop()
  }
  ...
}
```

#Recog session
The recog session is an FSM actor; it moves through different states depending on the messages it receives; it also maintians timeouts--when the user abandons a session, the session actor will remove itself once the timeout elapses.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  import RecogSessionActor._
  import scala.concurrent.duration._
  import context.dispatcher

  // default timeout for all states
  val stateTimeout = 60.seconds

  // thank goodness for British spelling :)
  val emptyBehaviour: StateFunction = { case _ => stay() }

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
    case Event(message, data) =>
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

  // go!
  initialize
}
```

Complicated? Yes. Difficult to write & understand? No! 

#AMQP
But we're counting coins in images! All we've seen so far is some data pushing in Scala. We need to connect our super-smart (T&Cs apply) compter vision code.

It sits on the other end of RabbitMQ; when we send the broker a message to the ``amq.direct`` exchange, using the ``count.key`` routing key, it will get routed to the running native code. The native code will pick up the message, take its payload; perform the coin counting using the hough circles transform and reply back with a JSON:

```json
{ 
   "coins":[{"center":123.3, "diameter":30.4}, {"center":400, "diameter":55}],
   "succeeded":true
}
```

In other words, we have the array of coins and indication whether we were able to successfully process the image.

So, let's wire in the AMQP connector and send it the messages.

#AMQP actor
We create the ``amqp`` actor whenever we create the ``RecogSessionActor``.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  import RecogSessionActor._
  import scala.concurrent.duration._
  import context.dispatcher

  // default timeout for all states
  val stateTimeout = 60.seconds

  // thank goodness for British spelling :)
  val emptyBehaviour: StateFunction = { case _ => stay() }

  // make a connection to the AMQP broker
  val amqp = ConnectionOwner.createChildActor(amqpConnection, Props(new RpcClient()))

  ...

}
```

Because it is not a child of this actor (it is a child of the AMQP connection owner actor--don't ask!), we must remember to clean it up in the ``postStop()`` function.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  ...

  // cleanup
  override def postStop() {
    context.stop(amqp)
  }

}
```

#Stopping
But wait? When **do** we stop? Intuitively, we sould like to stop this actor when we encounter a state timout or when we complete. That can all be defined as behaviour on transitions.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  ...

  // cleanup
  onTransition {
    case _ -> Aborted =>
      println("Aborting!")
      context.stop(self)
    case _ -> Completed =>
      println("Done!")
      context.stop(self)
  }

  ...
}
```

Right ho. We can now write the code to send the images to the components on the other end of RabbitMQ.

#Sending images
The recognition can deal with H.264 stream as well as individual images, but once you've sent the first frame of the stream or the first image, you must keep sending more frames or more images. In other words, you cannot combine H.264 and static frames.
This means that we can set up the ``DecoderContext`` on first input and simply keep it in our FSM state.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  ...
  // when ``Active``, we can process images and frames
  when(Active, stateTimeout) {
    case Event(Image(image, end), r@Running(minCoins, None)) if image.length > 0  =>
      val decoder = new NoopDecoderContext(countCoins(minCoins))
      decoder.decode(image, end)
      stay() using r.copy(decoder = Some(decoder))
    case Event(Image(image, end), Running(minCount, Some(decoder: ImageDecoderContext))) if image.length > 0  =>
      decoder.decode(image, end)
      stay()
    case Event(Image(_, _), Running(_, Some(decoder))) =>
      decoder.close()
      goto(Completed)

    case Event(Frame(frame, _), r@Running(minCoins, None)) =>
      val decoder = new H264DecoderContext(countCoins(minCoins))
      decoder.decode(frame, true)
      stay() using r.copy(decoder = Some(decoder))
    case Event(Frame(frame, _), Running(minCount, Some(decoder: VideoDecoderContext))) if frame.length > 0 =>
      decoder.decode(frame, true)
      stay()
    case Event(Frame(_, _), Running(_, Some(decoder))) =>
      decoder.close()
      goto(Completed)
  }
}
```

Now, the interesting function is the ``f: Array[Byte] => U`` function, which the current decoder calls when it has complete frame or image. Its job is to take the decoded frame and send it over RabbitMQ for processing.

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] {

  ...
  // when ``Active``, we can process images and frames
  when(Active, stateTimeout) {
    case Event(Image(image, end), r@Running(minCoins, None)) if image.length > 0  =>
      val decoder = new NoopDecoderContext(countCoins(minCoins))
      decoder.decode(image, end)
      stay() using r.copy(decoder = Some(decoder))
    ...
  }

  def countCoins(minCoins: Int)(image: Array[Byte]): Unit =
    amqpAsk(amqp)("amq.direct", "count.key", mkImagePayload(image)) onSuccess {
      case res => if (res.coins.size >= minCoins) jabberActor ! res
    }
}
```

#Asking AMQP
Let's keep peeling the onion and define the ``amqpAsk`` function. We'll put it in the ``AmqpOperations`` trait.

```scala
private[core] trait AmqpOperations {

  protected def amqpAsk(amqp: ActorRef)
                       (exchange: String, routingKey: String, payload: Array[Byte])
                       (implicit ctx: ExecutionContext): Future[String] = {
    import scala.concurrent.duration._
    import akka.pattern.ask

    val builder = new AMQP.BasicProperties.Builder
    implicit val timeout = Timeout(2.seconds)

    (amqp ? Request(Publish(exchange, routingKey, payload, Some(builder.build())) :: Nil)).map {
      case Response(Delivery(_, _, _, body)::_) => new String(body)
      case x => sys.error("Bad match " + x)
    }
  }

}
```

Right. The ``amqp`` ``ActorRef`` behaves just like ordinary Akka actor, except it sends the message over AMQP. Mixed into our ``RecogSessionActor`` makes us ready to move on!

#Stringly-typed
Oh, the humanity! We have ``Future[String]`` returned from the ``amqpAsk`` function. Stringly-typed code is not a good thing to have; we know that the response will be a JSON; one that matches

```scala
private[core] case class Coin(center: Double, radius: Double)
private[core] case class CoinResponse(coins: List[Coin], succeeded: Boolean)
```

Let's no modify the ``amqpAsk`` to deal with it. _Any suggestions?_

#Stand back. I know typeclasses!
Specifically, typeclass ``JsonReader[A]``, whose instances can turn some JSON into instances of ``A``. We then leave the compiler to do the dirty work for us and select the appropriate ``JsonReader[A]`` in our ``amqpAsk``. We need to make it polymorphic and ask the compiler to implicitly give us instance of ``JsonReader`` for that ``A``. Of course, we're now returning ``Future[A]``.

```scala
private[core] trait AmqpOperations {

  protected def amqpAsk[A](amqp: ActorRef)
                       (exchange: String, routingKey: String, payload: Array[Byte])
                       (implicit ctx: ExecutionContext, reader: JsonReader[A]): Future[A] = {
    import scala.concurrent.duration._
    import akka.pattern.ask

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
```

Goodie. But what about the instances of ``JsonReader`` for our ``CoinResponse`` and ``Coin``? We put them in yet another trait that we can mix in to our actor.

```scala
trait RecogSessionActorFormats extends DefaultJsonProtocol {
  import RecogSessionActor._

  implicit val CoinFormat = jsonFormat2(Coin)
  implicit val CoinResponseFormat = jsonFormat2(CoinResponse)
}
```

Now, when used in the ``RecogSessionActor``, we're now all set:

```scala
private[core] class RecogSessionActor(amqpConnection: ActorRef, jabberActor: ActorRef) extends Actor with
  FSM[RecogSessionActor.State, RecogSessionActor.Data] with
  AmqpOperations with
  ImageEncoding with
  RecogSessionActorFormats {


  def countCoins(minCoins: Int)(image: Array[Byte]): Unit =
    amqpAsk[CoinResponse](amqp)("amq.direct", "count.key", mkImagePayload(image)) onSuccess {
      case res => if (res.coins.size >= minCoins) jabberActor ! res
    }
}
```

#Let's now see...
We should be able to us the shell completely. Let's execute

```
begin:1
>>> 3ce43dc1-7397-4ab7-89ad-ecf70ebf681a
3ce43dc1-7397-4ab7-89ad-ecf70ebf681a/h264:/coins.mp4
...

quit
```

So, it works.

#REST
We have this funky, concurrent, scalable system; and yet, we use it from the command line. Not good. Let's give it some nice RESTful API that can deal with different clients. And, seeing how cool we all are, let's have iOS client.

Remember our ``Api`` trait? It's time to look in detail into what it does.

```scala
trait Api {
  this: Core =>

  val streamingRecogService = system.actorOf(Props(new StreamingRecogService(coordinator)))
  val ioBridge: ActorRef = IOExtension(system).ioBridge()
  val settings: ServerSettings = ServerSettings()

  system.actorOf(Props(new HttpServer(ioBridge, SingletonHandler(streamingRecogService), settings)), "http-server") ! HttpServer.Bind("0.0.0.0", 8080)

}
```

These incantations create the Spray-can server with the ``StreamingRecogService`` actor handling all the requests. Naturally, just like the ``Shell``, the ``StreamingRecogService`` needs to be given the ``ActorRef`` to the coordinator actor.

#StreamingRecogService
The ``StreamingRecogService`` now receives the messages; deals with the details of HTTP and hands over to the ``coordinator``.

```scala
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
      coordinator ! message
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
      sender ! HttpResponse(entity = "No such endpoint. That's all we know.", status = StatusCodes.NotFound)
  }

}
```

Just a bit of typing, is all! Notice though that we handle HTTP chunks. In other words, we expect our clients to send us the video stream by parts, not in one big chunk. 

#Let's play!
I happen to have my iPhone here with the app installed; and I've pre-recorded a video. Let's see how it all behaves.

* Observe the JVM console
* Observe RabbitMQ console

#Questions?

#Done

[@honzam399](https://twitter.com/honzam399) |
[janm@cakesolutions.net](mailto:janm@cakesolutions.net) |
[cakesolutions.net](http://www.cakesolutions.net) |
[github.com/janm399](https://github.com/janm399) |
[github.com/eigengo](https://github.com/eigengo)
