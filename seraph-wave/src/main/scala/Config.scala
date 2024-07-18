package com.seraphwave.config;

import org.bukkit.configuration.file.FileConfiguration

class Config(
    val audioSettings: AudioSettings,
    val metaInfo: ServerMetaInfo,
    val httpServer: ServerConfig
)

class ServerConfig(
    val host: String,
    val port: Int
)
class ServerMetaInfo(
  val welcomeMsg: String,
  val webSocketUrl: String,
)
class AudioSettings(
  val activationRadius: Double,
)

def fromFileConfig(config: FileConfiguration): Config = {
  val host = config.getString("http-server.host", "localhost")
  val port = config.getInt("http-server.port", 65437)
  
  val webSocketUrlF = config.getString("http-server.websocket-url", "auto")
  val webSocketUrl = if(webSocketUrlF == "auto") {
    s"ws://${host}:${port}/gateway"
  } else {
    webSocketUrlF
  }

  Config(
    audioSettings = AudioSettings(
      activationRadius = config.getDouble("audio-settings.activation-radius", 30.0)
    ),
    metaInfo = ServerMetaInfo(
      welcomeMsg = config.getString("meta-info.welcome-msg", "Welcome to proximity chat!"),
      webSocketUrl = webSocketUrl
    ),
    httpServer = ServerConfig(
      host = host,
      port = port
    )
  )
}
