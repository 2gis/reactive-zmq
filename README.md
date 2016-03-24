# Reactive ZMQ

This is [akka-stream](http://doc.akka.io/docs/akka/current/scala/stream/index.html) API for [zmq](http://zeromq.org)

![build status](https://api.travis-ci.com/2gis/reactive-zmq.svg?token=9x6XdQxD6LsyqgiNyJhx&branch=master)
[![Download](https://api.bintray.com/packages/2gis/maven/reactive-zmq/images/download.svg)](https://bintray.com/2gis/maven/reactive-zmq/_latestVersion)

# 30 seconds start

Add dependency to sbt:

```scala
"ru.dgis" %% "reactive-zmq" % "0.1.0"
```

Create `ZMQ.Socket`:

```scala
import org.zeromq.ZMQ
val context = ZMQ.context(1)
val socket = context.socket(ZMQ.PULL)
socket.setReceiveTimeOut(1000) // this should be >= 0
```

Create `Source` from `Socket`:

```scala
val source = ZMQSource(socket, List("127.0.0.1:12345"))
```

Now you may use `source` in your graphs:

```scala
implicit val as = ActorSystem()
implicit val m = ActorMaterializer()
source
  .map { x: ByteString => println(x); x }
  .to(Sink.ignore)
  .run()
```

Full example is available [here](https://github.com/2gis/reactive-zmq/tree/master/src/test/scala/ru/dgis/reactivezmq/Examples.scala)

# Stopping

To stop the `Source` you should use a `Control` object that can be obtained via materilization:

```scala
val (control, finish) = source
  .map { x: ByteString => println(x); x }
  .toMat(Sink.ignore)(Keep.both)
  .run()
```

The `control` exposes `gracefulStop` method which gracefully closes underlying socket and completes the `Source`:

```scala
control.gracefulStop()

implicit val ec = as.dispatcher
finish.onComplete { _ =>
  as.terminate()
  context.close()
}
```
