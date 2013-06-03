package org.eigengo.sd.api

import org.eigengo.sd.core.Core
import akka.actor.{ActorRef, Props}
import spray.can.server.{ServerSettings, HttpServer}
import spray.io.{IOExtension, SingletonHandler}

trait Api {
  this: Core =>

  val streamingRecogService = system.actorOf(Props(new StreamingRecogService(coordinator)))
  val ioBridge: ActorRef = IOExtension(system).ioBridge()
  val settings: ServerSettings = ServerSettings()

  system.actorOf(Props(new HttpServer(ioBridge, SingletonHandler(streamingRecogService), settings)), "http-server") ! HttpServer.Bind("0.0.0.0", 8080)


}
