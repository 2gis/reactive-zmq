package ru.dgis.reactivezmq

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, KillSwitches}
import akka.stream.scaladsl.{Keep, Sink}
import org.zeromq.ZMQ

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt

//import akka.actor.ActorSystem
//import akka.stream.ActorMaterializer
//import akka.stream.scaladsl.Source
//
//import scala.concurrent.duration.DurationInt
//import org.zeromq.ZMQ
//
//object Main extends App{
//  implicit val s: ActorSystem = ActorSystem()
//  implicit val mat: ActorMaterializer = ActorMaterializer()
//
//  val context = ZMQ.context(1)
//
//  val socket1 = context.socket(ZMQ.PUSH)
//  val socket2 = context.socket(ZMQ.PUB)
//
//  socket1.bind("tcp://127.0.0.1:12345")
//  socket2.bind("tcp://*:54321")
//
//  Source.tick(0.seconds, 1.second, 0: Byte).map(_ => java.time.Instant.now().toString.getBytes).runForeach{ba =>
//    socket1.send(ba)
//    ()
//  }
//  Source.tick(0.seconds, 2.second, 0: Byte).map(_ => java.time.Instant.now().toString.getBytes).runForeach{ba =>
//    socket2.send(ba, 0)
//    ()
//  }
//}

/**
  * This file provides compilable examples of API usage
  */
object Examples extends App {
  implicit val as: ActorSystem = ActorSystem()
  implicit val m: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = as.dispatcher

  val context = ZMQ.context(1)
  val s1 = ZMQSource(context, mode = ZMQ.PULL, timeout = 1.second, addresses = List("tcp://127.0.0.1:12345"))
  val s2 = ZMQSource(context, mode = ZMQ.SUB, timeout = 1.second, addresses = List("tcp://127.0.0.1:54321"), "")
  val (control1, finish1) = s1.viaMat(KillSwitches.single)(Keep.right)
    .toMat(Sink.foreach(b => println("s1: " + b.utf8String)))(Keep.both).run()
  val (control2, finish2) = s2.viaMat(KillSwitches.single)(Keep.right)
    .toMat(Sink.foreach(b => println("s2: " + b.utf8String)))(Keep.both).run()

  finish1.foreach(_ => control2.shutdown())

  finish2.onComplete { _ =>
    as.terminate()
    context.close()
  }
  Thread.sleep(60000)
  control1.shutdown()
}