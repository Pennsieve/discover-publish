Global / cancelable := true

// common settings
ThisBuild / resolvers ++= Seq(
  "Pennsieve Releases" at "https://nexus.pennsieve.cc/repository/maven-releases",
  "Pennsieve Snapshots" at "https://nexus.pennsieve.cc/repository/maven-snapshots",
) ++ Resolver.sonatypeOssRepos("snapshots")

ThisBuild / credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  "nexus.pennsieve.cc",
  sys.env("PENNSIEVE_NEXUS_USER"),
  sys.env("PENNSIEVE_NEXUS_PW")
)

// Temporarily disable Coursier because parallel builds fail on Jenkins.
// See https://app.clickup.com/t/a8ned9
ThisBuild / useCoursier := false

ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "com.pennsieve"
ThisBuild / organizationName := "University of Pennsylvania"
ThisBuild / licenses := List(
  "Apache-2.0" -> new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")
)
ThisBuild / startYear := Some(2021)

ThisBuild / version := sys.props.get("version").getOrElse("bootstrap-SNAPSHOT")

val publishToNexus =
  settingKey[Option[Resolver]]("Pennsieve Nexus repository resolver")

ThisBuild / publishToNexus := {
  val nexus = "https://nexus.pennsieve.cc/repository"
  if (isSnapshot.value) {
    Some("Nexus Realm" at s"$nexus/maven-snapshots")
  } else {
    Some("Nexus Realm" at s"$nexus/maven-releases")
  }
}

val remoteCacheLocation =
  sys.props.get("remote-cache").getOrElse("/tmp/sbt/pennsieve-api")
ThisBuild / pushRemoteCacheTo := Some(
  MavenCache("local-cache", file(remoteCacheLocation))
)

ThisBuild / scalafmtOnCompile := true

// Run tests in a separate JVM to prevent resource leaks.
ThisBuild / Test / fork := true

lazy val akkaVersion = "2.6.19"
lazy val akkaCirceVersion = "1.39.2"
lazy val akkaHttpVersion = "10.2.9"
lazy val akkaStreamContribVersion = "0.11"
lazy val alpakkaVersion = "4.0.0"
lazy val swaggerAkkaHttpVersion = "1.5.2"

lazy val auditMiddlewareVersion = "1.0.3"
lazy val authMiddlewareVersion = "5.1.3"
lazy val coreVersion = "350-3c297ea"

lazy val awsVersion = "1.11.931"
lazy val awsV2Version = "2.25.19"

lazy val catsVersion = "2.6.1"

lazy val circeVersion = "0.14.1"

lazy val circeDerivationVersion = "0.13.0-M5"

lazy val ficusVersion = "1.5.2"

lazy val flywayVersion = "4.2.0"

lazy val json4sVersion = "3.5.5"

lazy val jettyVersion = "9.1.3.v20140225"
lazy val postgresVersion = "42.7.3"
lazy val scalatraVersion = "2.7.1"

lazy val scalatestVersion = "3.2.11"

lazy val scalikejdbcVersion = "3.5.0"

lazy val slickVersion = "3.3.3"

lazy val slickPgVersion = "0.20.3"

lazy val slickCatsVersion = "0.10.4"

lazy val testContainersVersion = "0.40.1"
lazy val utilitiesVersion = "4-55953e4"
lazy val jobSchedulingServiceClientVersion = "6-3251c91"
lazy val serviceUtilitiesVersion = "9-b838dd9"
lazy val discoverServiceClientVersion = "98-bd315fe"
lazy val doiServiceClientVersion = "12-756107b"
lazy val timeseriesCoreVersion = "6-487b00c"
lazy val commonsIoVersion = "2.6"

lazy val enumeratumVersion = "1.7.0"

lazy val unwantedDependencies = Seq(
  ExclusionRule("commons-logging", "commons-logging"),
  // Drop core-models pulled in as a transitive dependency by clients
  ExclusionRule("com.typesafe.akka", "akka-protobuf-v3_2.13")
)

import sbtassembly.MergeStrategy
lazy val defaultMergeStrategy = settingKey[String => MergeStrategy](
  "Default mapping from archive member path to merge strategy. Used by all subprojects that build fat JARS"
)

ThisBuild / defaultMergeStrategy := {
  case PathList("META-INF", _ @_*) => MergeStrategy.discard
  case PathList("PropertyList-1.0.dtd", _ @_*) => MergeStrategy.last
  case PathList("codegen-resources", "customization.config", _ @_*) =>
    MergeStrategy.discard
  case PathList("codegen-resources", "examples-1.json", _ @_*) =>
    MergeStrategy.discard
  case PathList("codegen-resources", "paginators-1.json", _ @_*) =>
    MergeStrategy.discard
  case PathList("codegen-resources", "service-2.json", _ @_*) =>
    MergeStrategy.discard
  case PathList("codegen-resources", "waiters-2.json", _ @_*) =>
    MergeStrategy.discard
  case PathList("com", "google", "common", _ @_*) => MergeStrategy.first
  case PathList("com", "sun", _ @_*) => MergeStrategy.last
  case PathList("common-version-info.properties") => MergeStrategy.last
  case PathList("contribs", "mx", _ @_*) => MergeStrategy.last
  case PathList("core-default.xml") => MergeStrategy.last
  case PathList("digesterRules.xml") => MergeStrategy.last
  case PathList("groovy", _ @_*) => MergeStrategy.first
  case PathList("groovyjarjarcommonscli", _ @_*) => MergeStrategy.first
  case PathList("javax", _ @_*) => MergeStrategy.last
  case PathList("logback", _ @_*) => MergeStrategy.filterDistinctLines
  case PathList("logback.xml", _ @_*) => MergeStrategy.first
  case PathList("mime.types") => MergeStrategy.last
  case PathList("module-info.class") => MergeStrategy.discard
  case PathList("org", "apache", _ @_*) => MergeStrategy.last
  case PathList("org", "codehaus", _ @_*) => MergeStrategy.first
  case PathList("overview.html", _ @_*) => MergeStrategy.last
  case PathList("properties.dtd", _ @_*) => MergeStrategy.last
  case x => MergeStrategy.defaultMergeStrategy(x)
}

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-language:postfixOps",
    "-language:implicitConversions",
    "-feature",
    "-deprecation"
  ),
  assembly / test := {},
  libraryDependencies ++= Seq(
    "org.slf4j" % "slf4j-api" % "1.7.25",
    "org.slf4j" % "jul-to-slf4j" % "1.7.25",
    "org.slf4j" % "jcl-over-slf4j" % "1.7.25",
    "org.slf4j" % "log4j-over-slf4j" % "1.7.25",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "ch.qos.logback" % "logback-core" % "1.2.3",
    "net.logstash.logback" % "logstash-logback-encoder" % "5.2",
    "com.iheart" %% "ficus" % ficusVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
    "org.postgresql" % "postgresql" % postgresVersion,
    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion
  ),
  excludeDependencies ++= unwantedDependencies
)

// core settings
lazy val coreSettings = Seq(
  name := "pennsieve-core",
  publishTo := publishToNexus.value,
  Test / publishArtifact := true,
  Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
  publishMavenStyle := true,
  scalacOptions ++= Seq("-language:higherKinds"),
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "auth-middleware" % authMiddlewareVersion,
    "com.pennsieve" %% "job-scheduling-service-client" % jobSchedulingServiceClientVersion,
    "com.pennsieve" %% "service-utilities" % serviceUtilitiesVersion,
    "com.pennsieve" %% "utilities" % utilitiesVersion,
    "commons-codec" % "commons-codec" % "1.10",
    "commons-validator" % "commons-validator" % "1.6",
    "com.chuusai" %% "shapeless" % "2.3.6",
    "com.beachape" %% "enumeratum" % enumeratumVersion,
    "com.beachape" %% "enumeratum-circe" % enumeratumVersion,
    "com.beachape" %% "enumeratum-json4s" % enumeratumVersion,
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-generic-extras" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "io.circe" %% "circe-shapes" % circeVersion,
    "io.circe" %% "circe-derivation" % circeDerivationVersion,
    "com.github.swagger-akka-http" %% "swagger-scala-module" % "1.3.0",
    "com.amazonaws" % "aws-java-sdk-core" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-ecs" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-kms" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-s3" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-ses" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-ssm" % awsVersion,
    "software.amazon.awssdk" % "sns" % awsV2Version,
    "software.amazon.awssdk" % "sqs" % awsV2Version,
    "software.amazon.awssdk" % "cognitoidentityprovider" % awsV2Version,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.auth0" % "jwks-rsa" % "0.8.3",
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.8.1",
    "com.nimbusds" % "nimbus-jose-jwt" % "9.7" % Test
  ),
  excludeDependencies ++= unwantedDependencies
)

lazy val discoverPublishSettings = Seq(
  name := "discover-publish",
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % catsVersion,
    "commons-io" % "commons-io" % commonsIoVersion,
    "com.lightbend.akka" %% "akka-stream-alpakka-s3" % alpakkaVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-generic-extras" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "io.circe" %% "circe-shapes" % circeVersion,
    "io.circe" %% "circe-derivation" % circeDerivationVersion,
    "software.amazon.awssdk" % "s3" % awsV2Version,
    "software.amazon.awssdk" % "url-connection-client" % awsV2Version,
    "com.pennsieve" %% "pennsieve-core" % coreVersion,
    "com.pennsieve" %% "core-models" % coreVersion,
    "org.scalatest" %% "scalatest" % scalatestVersion % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
    "org.scalamock" %% "scalamock" % "5.2.0" % Test,
    "org.mock-server" % "mockserver-client-java-no-dependencies" % "5.14.0" % Test,
    "com.pennsieve" %% "pennsieve-core" % coreVersion % Test classifier "tests",
    "com.pennsieve" %% "migrations" % coreVersion % Test,
    "com.dimafeng" %% "testcontainers-scala" % testContainersVersion % Test
  ),
  excludeDependencies ++= unwantedDependencies,
  docker / dockerfile := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/openjdk:8-alpine3.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("java", "-jar", artifactTargetPath)
    }
  },
  docker / imageNames := Seq(ImageName("pennsieve/discover-publish:latest")),
  assembly / assemblyMergeStrategy := defaultMergeStrategy.value
)

lazy val multipartUploaderMain = Seq(
  name := "multipart-uploader",
  Compile / run / mainClass := Some("com.pennsieve.publish.MultipartUploaderMain")
)

// project definitions
lazy val `discover-publish` = project
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(coreSettings: _*)
  .settings(discoverPublishSettings: _*)
  .settings(multipartUploaderMain: _*)

lazy val root = (project in file("."))
  .aggregate(
    `discover-publish`,
  )
  .settings(commonSettings: _*)
