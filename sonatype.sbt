import xerial.sbt.Sonatype._

licenses := Seq("MPL-2.0" -> url("https://www.mozilla.org/en-US/MPL/2.0/"))

sonatypeProjectHosting := Some(GitHubHosting("2gis", "reactive-zmq", "s.savulchik@2gis.ru"))

publishTo := sonatypePublishToBundle.value

publishMavenStyle := true

sonatypeCredentialHost := "s01.oss.sonatype.org"

