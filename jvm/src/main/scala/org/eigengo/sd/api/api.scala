package org.eigengo.sd.api

import org.eigengo.sd.core.Core
import akka.actor.{ ActorRef, Props }
import spray.can.server.{ ServerSettings, HttpServer }
import spray.io.{ IOExtension, SingletonHandler }

/**
 * The REST API server. Uses Spray-can and Spray-can's chunked HTTP processing (see
 * the ``spray.can`` secion of the ``application.conf``).
 *
 * Apart from that, it requires the functionality of ``Core`` that it wraps in REST
 * API.
 */
trait Api {
  this: Core =>

  // our endpoints
  private val streamingRecogService = system.actorOf(Props(new StreamingRecogService(coordinator)))

  // Spray's IOExtension to the ActorSystem
  private val ioBridge: ActorRef = IOExtension(system).ioBridge()

  // Server settings--defaults will do just fine :)
  private val settings: ServerSettings = ServerSettings()

  // create the ``HttpServer`` actor, routing the requests to our ``streamingRecogService``
  // and then bind it to all local interfaces on port ``8080``
  system.actorOf(Props(new HttpServer(ioBridge, SingletonHandler(streamingRecogService), settings)), "http-server") ! HttpServer.Bind("0.0.0.0", 8080)

}
