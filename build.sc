import mill._, scalalib._
import mill.scalalib.Assembly._
import coursier.maven.MavenRepository

object `seraph-wave` extends ScalaModule {
  def scalaVersion = "3.4.2"
	def version = "0.1.1"

  def repositoriesTask = T.task {
    super.repositoriesTask() :+
      MavenRepository(
        "https://hub.spigotmc.org/nexus/content/repositories/snapshots/"
      )
  }

  def ivyDeps = Agg(
    ivy"org.http4s::http4s-core:0.23.16",
    ivy"org.http4s::http4s-dsl:0.23.16",
    ivy"org.http4s::http4s-blaze-server:0.23.16",
    ivy"org.http4s::http4s-circe:0.23.16",
    ivy"io.circe::circe-literal:0.14.9",
    ivy"io.circe::circe-generic:0.14.9",
    ivy"io.circe::circe-parser:0.14.9",
    ivy"org.typelevel::cats-core:2.12.0",
    ivy"org.typelevel::cats-effect:3.5.4",
    ivy"org.bouncycastle:bcpkix-jdk18on:1.78.1",
    ivy"org.bouncycastle:bcprov-jdk18on:1.78.1"
  )

  def compileIvyDeps = Agg(
    ivy"org.spigotmc:spigot-api:1.21-R0.1-SNAPSHOT"
  )

  override def resources = T.sources {
    super.resources() ++
      Seq(
        PathRef(millSourcePath / "src" / "main" / "resources"),
        PathRef(webResources())
      )
  }
	
	override def assembly = T {
		val assembled = super.assembly()

		val otherDest = T.dest / s"Seraphwave-$version.jar"
		os.copy(assembled.path, otherDest, replaceExisting = true)

		PathRef(otherDest)
	}

  def webResources = T {
    val tempDir = T.dest
    os.remove.all(tempDir / "svelte-build")
    os.copy(
      os.pwd / "seraph-wave-gui" / "build",
      tempDir / "svelte-build",
      mergeFolders = true
    )
    tempDir
  }
}
