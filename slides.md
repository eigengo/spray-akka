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

  val amqpConnection = system.actorOf(...)
  val coordinator = system.actorOf(Props(new CoordinatorActor(amqpConnection)), "coordinator")
}
```

```
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
