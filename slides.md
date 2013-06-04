!SLIDE

#Akka in heterogeneous environments

!SLIDE

#Counting coins

We have a video with coins on a table. We want to process the video, count the coins, and send IM on change of the count. 

In summary, we are building this system:

H.264 -- (HTTP) --> Scala/Akka -- (AMQP) --> C++/CUDA 
                        |                       |
                        +<------- (AMQP) -------+
                        |
                        +------- (Jabber) ------> 

The main components:

* Scala & Akka processing core (Maintain multiple recognition sessions, deal with integration points.)
* Spray for the REST API (Chunked HTTP posts to receive the video.)
* AMQP connecting the native components (Array[Byte] out; JSON in.)
* C++ / CUDA for the image processing (OpenCV with CUDA build. Coin counting logic.)
* iOS client that sends the H.264 stream (Make chunked POST request to the Spray endpoint.)

#What our code does
We support multiple recognition sessions; each session maintains its own state. The session _coordinator_ starts new sessions and routes request to existing sessions.

            [ Begin(params) ]
??? ----->  [ SingleImage(id, ...) ]  -----> [[ coordinator ]]
            [ FrameChunk(id, ...) ]                 |
                                               +----+----+
                                               |         | 
                                       ...  [[ S ]]   [[ S ]]  ...
                                               |
                                               |
                                            [[ A ]]

The coordinator receives the ``Begin`` message and creates a _session actor_, passing it the _jabber actor_; the _session actor_ in turn creates connection to RabbitMQ. On receiving the ``Begin`` message, the _session actor_ replies to the sender with the id of the session.

The _???_ component is responsible for initiating these messages; it is either a command-line app or it is a complete REST server app.

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