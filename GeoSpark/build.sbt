import sbt.Keys.{libraryDependencies, version}


lazy val root = (project in file(".")).
  settings(
    name := "PFlock",

    version := "0.1.0",

    scalaVersion := "2.11.11",

    organization := "edu.ucr.dblab",

    publishMavenStyle := true
  )

val SparkVersion = "2.1.0"

val SparkCompatibleVersion = "2.1"

val HadoopVersion = "2.7.2"

val GeoSparkVersion = "1.1.3"

val dependencyScope = "compile"

logLevel := Level.Warn

logLevel in assembly := Level.Error

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % SparkVersion % dependencyScope exclude("org.apache.hadoop", "*"),
  "org.apache.spark" %% "spark-sql" % SparkVersion % dependencyScope exclude("org.apache.hadoop", "*"),
  "org.apache.hadoop" % "hadoop-mapreduce-client-core" % HadoopVersion % dependencyScope,
  "org.apache.hadoop" % "hadoop-common" % HadoopVersion % dependencyScope,
  "org.datasyslab" % "geospark" % GeoSparkVersion,
  "org.datasyslab" % "geospark-sql_".concat(SparkCompatibleVersion) % GeoSparkVersion,
  "org.datasyslab" % "geospark-viz" % GeoSparkVersion,
  "org.datasyslab" % "JTSplus" % "0.1.4",
  "org.rogach" %% "scallop" % "3.1.5",
  "org.slf4j" % "slf4j-jdk14" % "1.7.25"
)

assemblyMergeStrategy in assembly := {
  case PathList("org.datasyslab", "geospark", xs@_*) => MergeStrategy.first
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case path if path.endsWith(".SF") => MergeStrategy.discard
  case path if path.endsWith(".DSA") => MergeStrategy.discard
  case path if path.endsWith(".RSA") => MergeStrategy.discard
  case _ => MergeStrategy.first
}

resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

resolvers +=
  "Open Source Geospatial Foundation Repository" at "http://download.osgeo.org/webdav/geotools"
