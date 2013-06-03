organization := "org.eigengo"

name := "scaladays2013"

version := "1.0.0"

scalaVersion := "2.10.1"

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-Xlint",
  "-language:_",
  "-target:jvm-1.7",
  "-encoding", "UTF-8"
)

resolvers ++= Seq(
  "Spray Releases" at "http://repo.spray.io",
  "Spray Releases" at "http://repo.spray.io",
  "Xugggler"       at "http://xuggle.googlecode.com/svn/trunk/repo/share/java/"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka"       %% "akka-actor"         % "2.1.2",
  "com.typesafe.akka"       %% "akka-slf4j"         % "2.1.2",
  "ch.qos.logback"           % "logback-classic"    % "1.0.11",
  "com.github.sstone"       %% "amqp-client"        % "1.1",
  "com.rabbitmq"             % "amqp-client"        % "2.8.1",
  "org.aspectj"              % "aspectjweaver"      % "1.7.2",
  "org.aspectj"              % "aspectjrt"          % "1.7.2",
  "xuggle"                   % "xuggle-xuggler"     % "5.4",
  "io.spray"                 % "spray-routing"      % "1.1-M7",
  "io.spray"                 % "spray-client"       % "1.1-M7",
  "io.spray"                %% "spray-json"         % "1.2.3",
  "io.spray"                 % "spray-testkit"      % "1.1-M7"    % "test",
  "com.typesafe.akka"       %% "akka-testkit"       % "2.1.2"     % "test"
)

parallelExecution in Test := false

//  retrieveManaged := true

transitiveClassifiers := Seq("sources")

initialCommands in console := "import org.eigengo.sd._,akka.actor._"

initialCommands in (Test, console) <<= (initialCommands in console)(_ + ",akka.testkit._")

scalariformSettings
