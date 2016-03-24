# Getting started guide

Let's create a simple processing graph and run it:

```scala
scala> import org.zeromq._
import org.zeromq._

scala> import ru.dgis.reactivezmq._
import ru.dgis.reactivezmq._

scala> import akka._
import akka._

scala> import akka.actor._
import akka.actor._

scala> import akka.util._
import akka.util._

scala> import akka.stream._
import akka.stream._

scala> import akka.stream.scaladsl._
import akka.stream.scaladsl._

scala> import scala.concurrent._
import scala.concurrent._

scala> import scala.concurrent.duration._
import scala.concurrent.duration._

scala> val zmqContext = ZMQ.context(1)
zmqContext: org.zeromq.ZMQ.Context = org.zeromq.ZMQ$Context@38efce8e

scala> def zmqSocketFactory = {
     |   val socket = zmqContext.socket(ZMQ.PULL)
     |   socket.setReceiveTimeOut(1000) // in millis, must be >= 0
     |   socket
     | }
zmqSocketFactory: org.zeromq.ZMQ.Socket

scala> implicit val sys = ActorSystem("reactive-zmq")
sys: akka.actor.ActorSystem = akka://reactive-zmq

scala> implicit val mat = ActorMaterializer()
mat: akka.stream.ActorMaterializer = ActorMaterializerImpl(akka://reactive-zmq,ActorMaterializerSettings(4,16,,<function1>,StreamSubscriptionTimeoutSettings(CancelTermination,5000 milliseconds),false,1000,false,true),akka.dispatch.Dispatchers@7494653,Actor[akka://reactive-zmq/user/StreamSupervisor-0#-1595583988],false,akka.stream.impl.SeqActorNameImpl@54581f22)

scala> implicit val ec = sys.dispatcher
ec: scala.concurrent.ExecutionContextExecutor = Dispatcher[akka.actor.default-dispatcher]

scala> val source = ZMQSource(zmqSocketFactory, addresses = List("tcp://127.0.0.1:6666"))
source: akka.stream.scaladsl.Source[akka.util.ByteString,ru.dgis.reactivezmq.ZMQSource.Control] = akka.stream.scaladsl.Source@3decb533

scala> val (control, finished) = source.toMat(Sink.foreach(println))(Keep.both).run()
control: ru.dgis.reactivezmq.ZMQSource.Control = ru.dgis.reactivezmq.ZMQSource$$anonfun$create$1$$anon$2@4e8ada7f
finished: scala.concurrent.Future[akka.Done] = List()

scala> finished onComplete { case _ =>
     |   sys.terminate()
     |   zmqContext.close()
     | }

scala> control.gracefulStop()

scala> Await.ready(sys.whenTerminated, 5.seconds)
res2: scala.concurrent.Future[akka.actor.Terminated] = Success(Terminated(Actor[akka://reactive-zmq/]))
```
