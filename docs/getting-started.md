# Getting started guide

Let's create a simple processing graph and run it:

```scala
import org.zeromq._
// import org.zeromq._

import ru.dgis.reactivezmq._
// import ru.dgis.reactivezmq._

import akka._
// import akka._

import akka.actor._
// import akka.actor._

import akka.util._
// import akka.util._

import akka.stream._
// import akka.stream._

import akka.stream.scaladsl._
// import akka.stream.scaladsl._

import scala.concurrent._
// import scala.concurrent._

import scala.concurrent.duration._
// import scala.concurrent.duration._

val zmqContext = ZMQ.context(1)
// zmqContext: org.zeromq.ZMQ.Context = org.zeromq.ZMQ$Context@6d515f3e

def zmqSocketFactory = {
  val socket = zmqContext.socket(ZMQ.PULL)
  socket.setReceiveTimeOut(1000) // in millis, must be >= 0
  socket
}
// zmqSocketFactory: org.zeromq.ZMQ.Socket

implicit val sys = ActorSystem("reactive-zmq")
// sys: akka.actor.ActorSystem = akka://reactive-zmq

implicit val mat = ActorMaterializer()
// mat: akka.stream.ActorMaterializer = ActorMaterializerImpl(akka://reactive-zmq,ActorMaterializerSettings(4,16,,<function1>,StreamSubscriptionTimeoutSettings(CancelTermination,5000 milliseconds),false,1000,false,true),akka.dispatch.Dispatchers@36babf5a,Actor[akka://reactive-zmq/user/StreamSupervisor-0#937460444],false,akka.stream.impl.SeqActorNameImpl@afec8dc)

implicit val ec = sys.dispatcher
// ec: scala.concurrent.ExecutionContextExecutor = Dispatcher[akka.actor.default-dispatcher]

val source = ZMQSource(zmqSocketFactory, addresses = List("tcp://127.0.0.1:6666"))
// source: akka.stream.scaladsl.Source[akka.util.ByteString,ru.dgis.reactivezmq.ZMQSource.Control] = akka.stream.scaladsl.Source@5ac54162

val (control, finished) = source.toMat(Sink.foreach(println))(Keep.both).run()
// control: ru.dgis.reactivezmq.ZMQSource.Control = ru.dgis.reactivezmq.ZMQSource$$anonfun$create$1$$anon$2@680f5f29
// finished: scala.concurrent.Future[akka.Done] = List()

finished onComplete { case _ =>
  sys.terminate()
  zmqContext.close()
}

control.gracefulStop()

Await.ready(sys.whenTerminated, 5.seconds)
// res2: scala.concurrent.Future[akka.actor.Terminated] = Success(Terminated(Actor[akka://reactive-zmq/]))
```
