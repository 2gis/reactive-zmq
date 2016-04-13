# Reactive ZMQ

This is [akka-stream](http://doc.akka.io/docs/akka/current/scala/stream/index.html) API for [zmq](http://zeromq.org).
Currently it only supports receiving data via unidirectional ZMQ sockets of types:
  - ZMQ.PULL
  - ZMQ.SUB

[![Build Status](https://travis-ci.org/2gis/reactive-zmq.svg?branch=master)](https://travis-ci.org/2gis/reactive-zmq)
[![Download](https://api.bintray.com/packages/2gis/maven/reactive-zmq/images/download.svg)](https://bintray.com/2gis/maven/reactive-zmq/_latestVersion)

# Prerequisites

  - Java 8
  - Scala 2.11.8+

# ZMQ compatibility

See [jeromq](https://github.com/zeromq/jeromq/tree/v0.3.5) documentation.  

# 30 seconds start

Add the following settings to your `build.sbt`:

```scala
resolvers += Resolver.jcenterRepo

libraryDependencies += "ru.dgis" %% "reactive-zmq" % "0.1.0"
```

Create zmq context and `Source`:

```scala
import org.zeromq.ZMQ
val context = ZMQ.context(1)
val source = ZMQSource(context,
  mode = ZMQ.PULL,
  timeout = 1 second,
  addresses = List("tcp://127.0.0.1:12345")
)
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

To stop the `Source` you should use the materialized `Control` object:

```scala
val (control, finish) = source
  .map { x: ByteString => println(x); x }
  .toMat(Sink.ignore)(Keep.both)
  .run()
```

The `Control` object exposes a `gracefulStop` method that closes an underlying ZMQ socket and completes the `Source`:

```scala
control.gracefulStop()

implicit val ec = as.dispatcher
finish.onComplete { _ =>
  as.terminate()
  context.close()
}
```

# Bleeding edge

Add the following settings to your `build.sbt` to use a SNAPSHOT version:

```scala
resolvers += "OSS JFrog Snapshots" at "https://oss.jfrog.org/artifactory/libs-snapshot/"

libraryDependencies += "ru.dgis" %% "reactive-zmq" % "0.2.0-SNAPSHOT"
```
