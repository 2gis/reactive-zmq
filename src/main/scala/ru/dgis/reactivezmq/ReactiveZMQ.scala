package ru.dgis.reactivezmq

import java.io.Closeable

import akka.actor.{ActorLogging, Props}
import akka.event.LoggingReceive
import akka.stream.ActorAttributes
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.zeromq.ZMQ

import scala.annotation.tailrec

object ZMQSource {
  /**
    * The means to control the Source after materialization.
    */
  trait Control {
    /**
      * Disconnect the underlying ZMQ socket, deliver the remaining data and finally close the socket.
      */
    def gracefulStop(): Unit
  }

  private[reactivezmq] def create(socketFactory: => ZMQSocket, addresses: List[String]): Source[ByteString, Control] =
    Source.actorPublisher[ByteString](ZMQActorPublisher.props(socketFactory, addresses))
      .mapMaterializedValue { ref =>
        new Control {
          def gracefulStop() = ref ! ZMQActorPublisher.GracefulStop
        }
      }
      .withAttributes(ActorAttributes.dispatcher("zmq-source-dispatcher"))
      .named("zmqSource")

  /**
    * Creates a Source of bytes wrapping a ZMQ socket provided by the factory.
    *
    * The sockets from the factory:
    *   - must have ZMQ.PULL or ZMQ.SUB type
    *   - must have a non-negative receive timeout set
    *
    * The Source:
    *   - emits when there is demand and the data available in the socket
    *   - completes when graceful stop is initiated and the remaining data is delivered from the socket
    *   - stops the delivery if downstream cancels the stream possibly loosing some data still remaining in the socket
    *
    * @param socketFactory a factory of ZMQ sockets
    * @param addresses a list of ZMQ endpoints to connect to
    * @return a Source of bytes
    */
  def apply(socketFactory: => ZMQ.Socket, addresses: List[String]): Source[ByteString, Control] =
    create(ZMQSocket(socketFactory), addresses)
}

private[reactivezmq] trait ZMQSocket extends Closeable {
  def getReceiveTimeOut: Int
  def connect(address: String): Unit
  def close(): Unit
  def recv: Array[Byte]
  def disconnect(address: String): Boolean
}

private object ZMQSocket {
  def apply(socket: ZMQ.Socket) = new ZMQSocket {
    def getReceiveTimeOut = socket.getReceiveTimeOut
    def recv = socket.recv()
    def disconnect(address: String) = socket.disconnect(address)
    def close() = socket.close()
    def connect(address: String) = socket.connect(address)
  }
}

private object ZMQActorPublisher {
  case object GracefulStop
  case object DeliverMore

  def props(socketFactory: => ZMQSocket, addresses: List[String]): Props =
    Props(new ZMQActorPublisher(socketFactory, addresses))
}

private class ZMQActorPublisher(socket: ZMQSocket, addresses: List[String]) extends ActorPublisher[ByteString] with ActorLogging {
  import ZMQActorPublisher._
  import akka.stream.actor.ActorPublisherMessage._

  require(socket.getReceiveTimeOut >= 0, "ZMQ socket receive timeout must be non-negative")

  override def preStart() = addresses foreach socket.connect
  override def postStop() = socket.close()

  def receive = idle

  def idle: Receive = LoggingReceive {
    case Request(n) =>
      if (deliver(n)) {
        self ! DeliverMore
        log.debug("idle ~> delivering")
        context.become(delivering)
      }
    case Cancel =>
      disconnect()
      context.stop(self)
    case GracefulStop =>
      disconnect()
      log.debug("idle ~> stopping")
      context.become(stopping)
  }

  def delivering: Receive = LoggingReceive {
    case Request(_) =>
    case DeliverMore =>
      if (deliver(totalDemand)) self ! DeliverMore
      else {
        log.debug("delivering ~> idle")
        context.become(idle)
      }
    case Cancel =>
      disconnect()
      context.stop(self)
    case GracefulStop =>
      disconnect()
      log.debug("delivering ~> delivering & stopping")
      context.become(deliveringAndStopping)
  }

  def stopping: Receive = LoggingReceive {
    case Request(n) => if (deliver(n)) onCompleteThenStop()
    case Cancel => context.stop(self)
    case GracefulStop =>
  }

  def deliveringAndStopping: Receive = LoggingReceive {
    case Request(_) =>
    case DeliverMore =>
      if (deliver(totalDemand)) onCompleteThenStop()
      else {
        log.debug("delivering & stopping ~> stopping")
        context.become(stopping)
      }
    case Cancel => context.stop(self)
    case GracefulStop =>
  }

  /**
    * @return need to deliver more
    */
  @tailrec
  private def deliver(n: Long): Boolean = {
    if (n == 0) false
    else {
      val data = Option(socket.recv).map(ByteString.apply)
      data foreach onNext
      if (data.nonEmpty) deliver(n - 1)
      else true
    }
  }

  def disconnect(): Unit = {
    addresses foreach socket.disconnect
  }
}
