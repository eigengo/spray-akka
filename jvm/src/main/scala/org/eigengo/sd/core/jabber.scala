package org.eigengo.sd.core

import akka.actor.Actor

/**
 * And you thought Jabber had something to do with [XMPP](http://en.wikipedia.org/wiki/XMPP)!
 * No such luck, it jabbers on to the standard output; I leave the actual Jabber code to the
 * curious programmer.
 */
class JabberActor extends Actor {

  // simply println all received messages.
  def receive = {
    case x => println(x)
  }
}
