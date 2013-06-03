package org.eigengo.sd.core

import akka.actor.Actor

class JabberActor extends Actor {

  def receive = {
    case x => println(x)
  }
}
