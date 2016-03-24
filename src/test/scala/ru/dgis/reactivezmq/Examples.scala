package ru.dgis.reactivezmq

import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import ru.dgis.reactivezmq.ZMQSource.Control


/**
  * This file provides compilable examples of API usage
  */
class Examples {

  import org.zeromq.ZMQ

  val context = ZMQ.context(1)
  val socket = context.socket(ZMQ.PULL)


  val source = ZMQSource(socket, List("127.0.0.1:12345"))

  implicit val as = ActorSystem()
  implicit val m = ActorMaterializer(ActorMaterializerSettings(as))
  val control: Control = source
    .map { x: ByteString => println(x); x }
    .to(Sink.ignore)
    .run()

  control.gracefulStop()
}
