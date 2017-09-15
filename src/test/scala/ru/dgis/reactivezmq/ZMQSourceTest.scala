package ru.dgis.reactivezmq

import java.util
import java.util.Collections
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import akka.util.ByteString
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{verify, when}
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpecLike, Inspectors, Matchers, BeforeAndAfterAll}
import org.zeromq.ZMQ

import scala.concurrent.duration._
import scala.language.reflectiveCalls

class ZMQSourceTest extends TestKit(ActorSystem("test"))
  with FlatSpecLike
  with MockitoSugar
  with Matchers
  with Inspectors
  with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  implicit val mat = ActorMaterializer()

  val genBytes = arbitrary[Byte].map(Array.apply(_))

  def fixture = new {
    val socket = new ZMQSocket {
      val getReceiveTimeOut = 500
      val getType = ZMQ.PULL

      private val queue = new LinkedTransferQueue[Array[Byte]]()
      def hasWaitingConsumer = queue.hasWaitingConsumer
      def recv = {
        if (closed) throw new IllegalStateException("Socket is closed")
        else Option(queue.poll(getReceiveTimeOut, MILLISECONDS)).filterNot(_ == DataUnavailable).orNull
      }
      def sendWithAwait(elems: Array[Byte]*) =
        elems foreach { elem =>
          if (!queue.tryTransfer(elem, remainingOrDefault.toMillis, MILLISECONDS))
            throw new IllegalStateException("Data was not received from the socket")
        }
      def send(elems: Array[Byte]*) = elems foreach queue.put
      def remainingElementsCount = queue.size()

      private val closedFlag = new AtomicBoolean(false)
      def close() = closedFlag.set(true)
      def closed = closedFlag.get()

      private val connectedTo = Collections.synchronizedSet(new util.HashSet[String]())
      def connect(address: String) = connectedTo.add(address)
      def disconnect(address: String) = connectedTo.remove(address)
      def connected = !connectedTo.isEmpty
    }

    val source = ZMQSource.create(() => socket, addresses = List("foo", "bar"))
  }

  def elements = Stream.continually(genBytes.sample.get)
  val DataUnavailable = "DataUnavailable".getBytes

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
      .request(elems.size)
      .expectNextN(elems.map(ByteString.apply))
  }

  it should "deliver the requested number of elements even if not immediately available in the socket" in {
    val f = fixture
    val elems = elements.take(10).toList
    val (head, tail) = elems.splitAt(5)
    f.socket.send(head ++: DataUnavailable +: tail: _*)
    f.source
      .runWith(TestSink.probe[ByteString])
      .request(elems.size)
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

  it should "disconnect the socket after receiving graceful stop command and become waiting for demand" in {
    val f = fixture
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    control.gracefulStop()
    probe.expectSubscription()
    probe.expectNoMsg(100.millis)
    awaitAssert { f.socket should not be 'connected }
    awaitAssert { f.socket should not be 'closed }
  }

  it should "disconnect the socket after receiving graceful stop command, try to deliver the requsted number of elements, complete the stream and close the socket" in {
    val f = fixture
    f.socket.send(DataUnavailable)
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    val res = control.gracefulStop()
    probe.expectSubscriptionAndComplete()
    awaitAssert { f.socket should not be 'connected }
    awaitAssert { f.socket shouldBe 'closed }
    awaitAssert { res.value.get.isSuccess shouldBe true }
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

  it should "disconnect the socket after receiving graceful stop command, try to deliver the requsted number of elements, complete the stream and close the socket" in {
    val f = fixture
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    probe.request(1)
    f.socket.send(DataUnavailable)
    val res = control.gracefulStop()
    f.socket.send(DataUnavailable)
    probe.expectComplete()
    awaitAssert { f.socket should not be 'connected }
    awaitAssert { f.socket shouldBe 'closed }
    awaitAssert { res.value.get.isSuccess shouldBe true }
  }

  it should "disconnect and close the socket if cancelled" in {
    val f = fixture
    f.socket.send(DataUnavailable)
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    probe
      .request(1)
      .cancel()
      .expectNoMsg(100.millis)
    awaitAssert { f.socket should not be 'connected }
    awaitAssert { f.socket shouldBe 'closed }
  }

  "ZMQSource when stopping" should "not deliver more elements than was demanded" in {
    val f = fixture
    val elems = elements.take(10).toList
    f.socket.send(elems: _*)
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    control.gracefulStop()
    probe
      .request(5)
      .expectNextN(elems.take(5).map(ByteString.apply))
      .expectNoMsg(100.millis)
    f.socket.remainingElementsCount shouldBe 5
  }

  it should "close the socket if cancelled" in {
    val f = fixture
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    control.gracefulStop()
    probe
      .cancel()
      .expectNoMsg(100.millis)
    awaitAssert { f.socket shouldBe 'closed }
  }

  it should "ignore other graceful stop commands" in {
    val f = fixture
    f.socket.send(DataUnavailable)
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    control.gracefulStop()
    control.gracefulStop()
    probe
      .expectSubscriptionAndComplete()
      .expectNoMsg(100.millis)
    awaitAssert { f.socket shouldBe 'closed }
  }

  it should "resolve returned futures after graceful stop" in {
    val f = fixture
    f.socket.send(DataUnavailable)
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    val res1 = control.gracefulStop()
    val res2 = control.gracefulStop()
    probe
      .expectSubscriptionAndComplete()
      .expectNoMsg(100.millis)
    awaitAssert { res1.value.get.isSuccess shouldBe true }
    awaitAssert { res2.value.get.isSuccess shouldBe true }
  }

  it should "return failed result when stream is cancelled while graceful stop process" in {
    val f = fixture
    f.socket.send(DataUnavailable)
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    val res = control.gracefulStop()
    probe.cancel()
    awaitAssert { a [GracefulStopInterrupted] should be thrownBy res.value.get.get }
  }

  "ZMQSource when delivering & stopping" should "deliver requested elements and wait for more demand" in {
    val f = fixture
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    probe.request(1)
    awaitCond(f.socket.hasWaitingConsumer)
    control.gracefulStop()
    f.socket.sendWithAwait(DataUnavailable) // to ensure transition to delivering & stopping state
    val elems = elements.take(1).toList
    f.socket.sendWithAwait(elems: _*)
    probe
      .expectNextN(elems.map(ByteString.apply))
      .expectNoMsg(100.millis)
    awaitAssert { f.socket should not be 'closed }
  }

  it should "stop immediately if cancelled" in {
    val f = fixture
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    probe.request(1)
    awaitCond(f.socket.hasWaitingConsumer)
    control.gracefulStop()
    probe.cancel()
    f.socket.sendWithAwait(DataUnavailable) // transition to delivering & stopping state
    probe.expectNoMsg(100.millis)
    awaitAssert { f.socket shouldBe 'closed }
  }

  it should "ignore other graceful stop commands" in {
    val f = fixture
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    probe.request(1)
    awaitCond(f.socket.hasWaitingConsumer)
    control.gracefulStop()
    control.gracefulStop()
    f.socket.sendWithAwait(DataUnavailable) // to ensure transition to delivering & stopping state
    f.socket.sendWithAwait(DataUnavailable) // to initiate stop
    probe.expectComplete()
    awaitAssert { f.socket shouldBe 'closed }
  }

  it should "resolve returned futures after graceful stop" in {
    val f = fixture
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    probe.request(1)
    awaitCond(f.socket.hasWaitingConsumer)
    val res1 = control.gracefulStop()
    val res2 = control.gracefulStop()
    f.socket.sendWithAwait(DataUnavailable) // to ensure transition to delivering & stopping state
    probe.expectComplete()
    awaitAssert { res1.value.get.isSuccess shouldBe true }
    awaitAssert { res2.value.get.isSuccess shouldBe true }
  }

  it should "return failed result when stream is cancelled while graceful stop process" in {
    val f = fixture
    val (control, probe) = f.source.toMat(TestSink.probe[ByteString])(Keep.both).run()
    probe.request(1)
    awaitCond(f.socket.hasWaitingConsumer)
    val res = control.gracefulStop()
    f.socket.sendWithAwait(DataUnavailable) // to ensure transition to delivering & stopping state
    probe.cancel()
    awaitAssert { a [GracefulStopInterrupted] should be thrownBy res.value.get.get }
  }
}
