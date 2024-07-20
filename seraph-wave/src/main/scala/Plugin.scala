package com.seraphwave;

import org.bukkit.plugin.java.JavaPlugin;
import com.seraphwave.server.HttpServer
import cats.effect.kernel.Resource
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.seraphwave.config._
import com.seraphwave.config.CertUtils.getOrCreateCert
import com.seraphwave.data.CodeMgr

implicit val runtime: IORuntime = cats.effect.unsafe.IORuntime.global

class Plugin extends JavaPlugin {
  private var serverResource: Option[Resource[IO, org.http4s.server.Server]] =
    None
  private var httpInstance1: HttpServer = null
  private var codeMgr: CodeMgr = null

  override def onEnable(): Unit = {
    Plugin.instance1 = this
    // onEnable
    this.saveDefaultConfig()

    this.getCommand("joinprox").setExecutor(commands.JoinProx())

    val config = fromFileConfig(this.getConfig())

    serverResource match {
      case Some(_) => {}
      case None => {
        startHttp(config)
      }
    }

    getServer().getPluginManager().registerEvents(EventListener(), this)
  }
  private def startHttp(config: Config): Unit = {
    {
      for {
        codeMgr <- CodeMgr()
        v <- codeMgr.load()
        _ <- v match {
          case Right(_) => IO.pure(())
          case Left(_) => throw new Exception("Cannot load codes file.")
        }
        server <- HttpServer.newServer(config)
        sslContext <- config.httpServer.https match {
          case true => getOrCreateCert().map(Some.apply)
          case false => IO.pure(None)
        }
        rsc <- IO({
          this.codeMgr = codeMgr
          this.httpInstance1 = server
          val serverResource = server.startServer(sslContext)
          this.serverResource = Some(serverResource)
          serverResource
        })
        value <- rsc.useForever
      } yield (value)
    }.unsafeRunAsync {
      case Left(e) =>
        getLogger().warning(s"Failed to start HTTP server: $e")
      case Right(_) => getLogger().info("HTTP server started successfully!")
    }
  }
  override def onDisable(): Unit = {
    Plugin.codeMgr.save().unsafeRunSync()
  }
}
object Plugin {
  private var instance1: Plugin = null
  def instance: Plugin = instance1
  def httpInstance = instance.httpInstance1
  def codeMgr = instance.codeMgr
}

