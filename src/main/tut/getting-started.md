# Getting started guide

Let's create a simple processing graph and run it:

```tut
import org.zeromq._
import ru.dgis.reactivezmq._
import akka._
import akka.actor._
import akka.util._
import akka.stream._
import akka.stream.scaladsl._
import scala.concurrent._
import scala.concurrent.duration._

val zmqContext = ZMQ.context(1)
def zmqSocketFactory = {
  val socket = zmqContext.socket(ZMQ.PULL)
  socket.setReceiveTimeOut(1000) // in millis, must be >= 0
  socket
}

implicit val sys = ActorSystem("reactive-zmq")
implicit val mat = ActorMaterializer()
implicit val ec = sys.dispatcher

val source = ZMQSource(zmqSocketFactory, addresses = List("tcp://127.0.0.1:6666"))
val (control, finished) = source.toMat(Sink.foreach(println))(Keep.both).run()

finished onComplete { case _ =>
  sys.terminate()
  zmqContext.close()
}

control.gracefulStop()

Await.ready(sys.whenTerminated, 5.seconds)
```
