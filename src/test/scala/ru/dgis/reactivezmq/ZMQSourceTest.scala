package ru.dgis.reactivezmq

import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.atomic.AtomicBoolean

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, KillSwitches}
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import akka.util.ByteString
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{verify, when}
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpecLike, Inspectors, Matchers}
import org.zeromq.ZMQ

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.reflectiveCalls

class ZMQSourceTest extends TestKit(ActorSystem("test")) with FlatSpecLike with MockitoSugar with Matchers with Inspectors {
  implicit val mat: ActorMaterializer = ActorMaterializer()

  val genBytes: Gen[Array[Byte]] = arbitrary[Byte].map(Array.apply(_))

  def fixture = new {
    val socket = new ZMQSocket {
      val getReceiveTimeOut: Int = 500
      val getType: Int = ZMQ.PULL
      private val queue = new LinkedTransferQueue[Array[Byte]]()
      def hasWaitingConsumer: Boolean = queue.hasWaitingConsumer
      def recvF(implicit ec: ExecutionContext) = Future{
        if (closed) throw new IllegalStateException("Socket is closed")
        @tailrec def f: Array[Byte] = {
          val data = queue.poll()
          if(!(data sameElements  DataUnavailable)) data else f
        }
        f
      }

      def send(elems: Array[Byte]*): Unit = elems foreach queue.put
      def remainingElementsCount: Int = queue.size()

      private val closedFlag = new AtomicBoolean(false)
      def close(): Unit = closedFlag.set(true)
      def closed: Boolean = closedFlag.get()

      private val connectedTo = java.util.Collections.synchronizedSet(new java.util.HashSet[String]())
      def connect(address: String): Unit = {
        connectedTo.add(address)
        ()
      }
      def disconnect(address: String): Boolean = connectedTo.remove(address)
      def connected: Boolean = !connectedTo.isEmpty
    }

    val source: Source[ByteString, NotUsed] = ZMQSource.create(() => socket, addresses = List("foo", "bar"))
  }

  def elements: Stream[Array[Byte]] = Stream.continually(genBytes.sample.get)
  val DataUnavailable: Array[Byte] = "DataUnavailable".getBytes

  "ZMQSource" should "connect to the provided addresses at start" in {
    val socket = mock[ZMQSocket]
    when(socket.getType).thenReturn(ZMQ.PULL)
    val addresses = List("foo", "bar", "baz")
    ZMQSource.create(() => socket, addresses)
      .runWith(TestSink.probe[ByteString])
      .expectSubscription()
    forAll(addresses) { verify(socket).connect(_) }
  }

  it should "terminate the stream due to unsupported ZMQ socket type and close the socket" in {
    val socket = mock[ZMQSocket]
    when(socket.getType).thenReturn(ZMQ.PUSH)
    ZMQSource.create(() => socket, addresses = Nil)
      .runWith(TestSink.probe)
      .expectSubscriptionAndError()
    awaitAssert { verify(socket).close() }
  }

  it should "terminate the stream due to infinite receive timeout on ZMQ socket and close the socket" in {
    val socket = mock[ZMQSocket]
    when(socket.getType).thenReturn(ZMQ.PULL)
    when(socket.getReceiveTimeOut).thenReturn(-1)
    ZMQSource.create(() => socket, addresses = Nil)
      .runWith(TestSink.probe)
      .expectSubscriptionAndError()
    awaitAssert { verify(socket).close() }
  }

  it should "terminate the stream due to connection problems and close the socket" in {
    val socket = mock[ZMQSocket]
    when(socket.getType).thenReturn(ZMQ.PULL)
    when(socket.connect(anyString())).thenThrow(classOf[Exception])
    ZMQSource.create(() => socket, addresses = List("42"))
      .runWith(TestSink.probe)
      .expectSubscriptionAndError()
    awaitAssert { verify(socket).close() }
  }

  "ZMQSource when idle" should "deliver the requested number of elements if immediately available in the socket" in {
    val f = fixture
    val elems = Gen.nonEmptyListOf(genBytes).sample.get
    f.socket.send(elems: _*)
    f.source
      .runWith(TestSink.probe[ByteString])
      .request(elems.size.toLong)
      .expectNextN(elems.map(ByteString.apply))
  }

  it should "deliver the requested number of elements even if not immediately available in the socket" in {
    val f = fixture
    val elems = elements.take(10).toList
    val (head, tail) = elems.splitAt(5)
    f.socket.send(head ++: DataUnavailable +: tail: _*)
    f.source
      .runWith(TestSink.probe[ByteString])
      .request(elems.size.toLong)
      .expectNextN(elems.map(ByteString.apply))
      .expectNoMsg(100.millis)
  }

  it should "not deliver more elements than was requested" in {
    val f = fixture
    val elems = elements.take(10).toList
    f.socket.send(elems: _*)
    f.source
      .runWith(TestSink.probe[ByteString])
      .request(2)
      .expectNextN(elems.take(2).map(ByteString.apply))
      .expectNoMsg(100.millis)
    f.socket.remainingElementsCount shouldBe 8
  }

  it should "disconnect and close the socket if cancelled" in {
    val f = fixture
    f.source
      .runWith(TestSink.probe[ByteString])
      .cancel()
      .expectNoMsg(100.millis)
    awaitAssert { f.socket should not be 'connected }
    awaitAssert { f.socket shouldBe 'closed }
  }

  it should "disconnect the socket after receiving graceful stop command, try to deliver the requsted number of elements, complete the stream and close the socket" in {
    val f = fixture
    f.socket.send(DataUnavailable)
    val (control, probe) = f.source.viaMat(KillSwitches.single)(Keep.right).toMat(TestSink.probe[ByteString])(Keep.both).run()
    control.shutdown()
    probe.expectSubscriptionAndComplete()
    awaitAssert { f.socket should not be 'connected }
    awaitAssert { f.socket shouldBe 'closed }
  }

  "ZMQSource when delivering" should "accumulate demand and delivery all the elements" in {
    val f = fixture
    val elems = elements.take(10).toList
    f.socket.send(DataUnavailable :: elems: _*)
    f.source
      .runWith(TestSink.probe[ByteString])
      .request(2)
      .request(3)
      .request(5)
      .expectNextN(elems.map(ByteString.apply))
  }

  it should "not deliver more elements than was demanded" in {
    val f = fixture
    val elems = elements.take(10).toList
    f.socket.send(DataUnavailable :: elems: _*)
    f.source
      .runWith(TestSink.probe[ByteString])
      .request(2)
      .expectNextN(elems.take(2).map(ByteString.apply))
      .expectNoMsg(100.millis)
    f.socket.remainingElementsCount shouldBe 8
  }

  it should "disconnect and close the socket if cancelled" in {
    val f = fixture
    f.socket.send(DataUnavailable)
    val probe = f.source.runWith(TestSink.probe[ByteString])
    probe
      .request(1)
      .cancel()
      .expectNoMsg(100.millis)
    awaitAssert { f.socket should not be 'connected }
    awaitAssert { f.socket shouldBe 'closed }
  }

  it should "ignore other graceful stop commands" in {
    val f = fixture
    f.socket.send(DataUnavailable)
    val (control, probe) = f.source.viaMat(KillSwitches.single)(Keep.right).toMat(TestSink.probe[ByteString])(Keep.both).run()
    control.shutdown()
    control.shutdown()
    probe
      .expectSubscriptionAndComplete()
      .expectNoMsg(100.millis)
    awaitAssert { f.socket shouldBe 'closed }
  }

  "ZMQSource when stopping" should "not deliver more elements than was demanded" in {
    val f = fixture
    val elems = elements.take(10).toList
    f.socket.send(elems: _*)
    val probe = f.source.runWith(TestSink.probe[ByteString])
    probe
      .request(5)
      .expectNextN(elems.take(5).map(ByteString.apply))
      .expectNoMsg(100.millis)
    f.socket.remainingElementsCount shouldBe 5
  }

  it should "close the socket if cancelled" in {
    val f = fixture
    val probe = f.source.runWith(TestSink.probe[ByteString])
    probe
      .cancel()
      .expectNoMsg(100.millis)
    awaitAssert { f.socket shouldBe 'closed }
  }


  it should "stop immediately if cancelled" in {
    val f = fixture
    val probe = f.source.runWith(TestSink.probe[ByteString])
    probe.request(1)
    probe.cancel()
    probe.expectNoMsg(100.millis)
    awaitAssert { f.socket shouldBe 'closed }
  }
}
