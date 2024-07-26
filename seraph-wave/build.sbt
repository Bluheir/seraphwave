val scala3Version = "3.4.2"

name := "Seraphwave"

version := "0.1"

scalaVersion := scala3Version
javacOptions ++= Seq("-source", "21", "-target", "21")
resolvers += "spigotmc-repo" at "https://hub.spigotmc.org/nexus/content/repositories/snapshots/"

libraryDependencies ++= Seq(
  "org.spigotmc" % "spigot-api" % "1.21-R0.1-SNAPSHOT" % "provided",
  
  "org.http4s" %% "http4s-core" % "0.23.16",
  "org.http4s" %% "http4s-dsl" % "0.23.16",
  "org.http4s" %% "http4s-blaze-server" % "0.23.16",
  "org.http4s" %% "http4s-circe" % "0.23.16",

  "io.circe" %% "circe-literal" % "0.14.9",
  "io.circe" %% "circe-generic" % "0.14.9",
  "io.circe" %% "circe-parser" % "0.14.9",
  
  "org.typelevel" %% "cats-core" % "2.12.0",
  "org.typelevel" %% "cats-effect" % "3.5.4",

  "org.bouncycastle" % "bcpkix-jdk18on" % "1.78.1",
  "org.bouncycastle" % "bcprov-jdk18on" % "1.78.1"
)


assembly / assembledMappings += {
  import scala.collection._
  import java.nio.file.Paths

  val sourceDir = baseDirectory.value / ".." / "seraph-wave-gui" / "build"

  def recursiveMap(srcDir: File, target: String): Seq[(File, String)] = {
    IO.listFiles(srcDir).map {
      case file @ _ if file.isDirectory => recursiveMap(file, Paths.get(target, file.getName).toString)
      case file @ _ => Seq((file -> Paths.get(target, file.getName).toString))
    }.toSeq.flatten
  }

  sbtassembly.MappingSet(None, (
      recursiveMap(sourceDir, "svelte-build") ++
      Seq(
        (file("..") / "LICENSE.md" -> "LICENSE.md"),
        (file("target") / "license-reports" / "seraphwave-licenses.md" -> "LICENSE-REPORT.md")
      )
    ).toVector
  )
}

assembly / assemblyJarName := s"Seraphwave-${version.value}.jar"
assembly / assemblyMergeStrategy := {
 case PathList("META-INF", _*) => MergeStrategy.discard
 case _                        => MergeStrategy.first
}
assembly / scalaVersion := scala3Version

enablePlugins(SbtProguard)

Proguard / proguardVersion := "7.5.0"

Proguard / proguardOptions ++= Seq(
  "-dontnote",
  "-dontwarn",
  "-ignorewarnings",
  "-dontobfuscate",
  "-dontoptimize",
  "-keep class com.seraphwave.* { *; }",
  "-keep class org.http4s.** { *; }",
)

Proguard / proguardOutputs := Seq(target.value / s"scala-${scala3Version}" / s"Seraphwave-${version.value}-MIN.jar")
Proguard / proguard / javaOptions := Seq("-Xmx4G")
Proguard / proguardInputs := Seq((assembly / assemblyOutputPath).value)
Proguard / proguardMerge := false
Proguard / proguardInputFilter := { file => None }

updateOptions := updateOptions.value.withLatestSnapshots(false)