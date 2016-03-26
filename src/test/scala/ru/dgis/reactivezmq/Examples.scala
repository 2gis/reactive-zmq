package ru.dgis.reactivezmq

import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import org.zeromq.ZMQ

/**
  * This file provides compilable examples of API usage
  */
object Examples extends App {
  val context = ZMQ.context(1)
  val socket = context.socket(ZMQ.PULL)
  socket.setReceiveTimeOut(1000)

  val source = ZMQSource(() => socket, List("tcp://127.0.0.1:12345"))

  implicit val as = ActorSystem()
  implicit val m = ActorMaterializer()
  val (control, finish) = source
    .map { x: ByteString => println(x); x }
    .toMat(Sink.ignore)(Keep.both)
    .run()

  implicit val ec = as.dispatcher
  control.gracefulStop()
  finish.onComplete { _ =>
    as.terminate()
    context.close()
  }
}
