package ru.dgis.reactivezmq

import java.io.Closeable

import akka.NotUsed
import akka.stream.{ActorAttributes, Attributes, Outlet, SourceShape}
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.util.ByteString
import org.zeromq.ZMQ
import org.zeromq.ZMQ.Poller

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

object ZMQSource {

  private[reactivezmq] def create(socketFactory: () => ZMQSocket, addresses: List[String]): Source[ByteString, NotUsed] =
    Source.fromGraph[ByteString, NotUsed](new ZMQSourceStage(socketFactory, addresses))
      .withAttributes(ActorAttributes.dispatcher("zmq-source-dispatcher"))
      .named("zmqSource")

  /**
    * Creates a ZMQ socket and wraps it with a Source
    *
    * The Source:
    *   - emits when there is demand and the data available in the socket
    *   - completes when graceful stop is initiated and the remaining data is delivered from the socket
    *   - stops the delivery if downstream cancels the stream possibly loosing some data still remaining in the socket
    *
    * @param context context to create socket with
    * @param mode socket type to be created. Must be ZMQ.PULL or ZMQ.SUB
    * @param timeout receive timeout for socket
    * @param addresses a list of ZMQ endpoints to connect to
    * @param topics a list of topics when using ZMQ.SUB
    * @return a Source of bytes
    */
  def apply(context: ZMQ.Context, mode: Int, timeout: FiniteDuration, addresses: List[String], topics: String*): Source[ByteString, NotUsed] = {
    require(mode == ZMQ.PULL || topics.nonEmpty, "Must provide at least one topic when using ZMQ.SUB")
    create(() => ZMQSocket(context, mode, timeout, topics:_*), addresses)
  }
}

trait ZMQSocket extends Closeable {
  def getReceiveTimeOut: Int
  def getType: Int
  def connect(address: String): Unit
  def close(): Unit
  def recvF(implicit ec: ExecutionContext): Future[Array[Byte]]
  def disconnect(address: String): Boolean
}

private object ZMQSocket {
  def apply(context: ZMQ.Context, mode: Int, timeout: FiniteDuration, topics: String*) = new ZMQSocket {
    private val socket = {
      val socket = context.socket(mode)
      socket.setReceiveTimeOut(timeout.toMillis.toInt)
      socket
    }
    private val poller = context.poller(1)
    poller.register(socket, Poller.POLLIN)
    def getReceiveTimeOut: Int = socket.getReceiveTimeOut
    def getType: Int = socket.getType
    def recvF(implicit ec: ExecutionContext) = Future{
      poller.poll()
      socket.recv()
    }
    def disconnect(address: String): Boolean = socket.disconnect(address)
    def close(): Unit = socket.close()
    def connect(address: String): Unit = {
      socket.connect(address)
      if(mode == ZMQ.SUB) topics.foreach(s => socket.subscribe(s.getBytes))
    }
  }
}

class ZMQSourceStage(socketFactory: () => ZMQSocket, addresses: List[String]) extends GraphStage[SourceShape[ByteString]]{
  private val out: Outlet[ByteString] = Outlet("ZMQSource.out")
  val shape: SourceShape[ByteString] = SourceShape(out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new ZMQSourceStageLogic(socketFactory.apply(), addresses, shape, out)
}

private class ZMQSourceStageLogic(socket: ZMQSocket, addresses: List[String], shape: SourceShape[ByteString], out: Outlet[ByteString]) extends GraphStageLogic(shape) with StageLogging{
  private val successCallback: AsyncCallback[ByteString] =
    getAsyncCallback(handleSuccess)
  private val failureCallback: AsyncCallback[Throwable] =
    getAsyncCallback(t => handleFailure(t, "Caught Exception. Failing stage..."))
  private val failureWithMessageCallback: AsyncCallback[(Throwable, String)] =
    getAsyncCallback(p => (handleFailure _).tupled(p))

  def handleSuccess(v: ByteString): Unit = push(out, v)
  def handleFailure(t: Throwable, msg: String): Unit = {
    log.error(t, msg)
    failStage(t)
  }

  override def preStart(): Unit = Try{
    require(socket.getReceiveTimeOut >= 0, "ZMQ socket receive timeout must be non-negative")
    require(socket.getType == ZMQ.PULL || socket.getType == ZMQ.SUB, "ZMQ socket type must be ZMQ.PULL or ZMQ.SUB")
  } match {
    case Failure(t: Throwable) =>
      failureWithMessageCallback.invoke(t -> "ZMQ socket requirements weren't met")
    case Success(_) =>
      Try(addresses foreach socket.connect) match {
        case Failure(t) =>
          failureCallback.invoke(t)
        case Success(_) =>
          ()
      }
  }

  override def postStop(): Unit = {
    addresses foreach socket.disconnect
    socket.close()
  }

  setHandler(out, new OutHandler {
    override def onPull(): Unit =
      socket.recvF(materializer.executionContext)
        .foreach(ba => successCallback.invoke(ByteString(ba)))(materializer.executionContext)
  })
}