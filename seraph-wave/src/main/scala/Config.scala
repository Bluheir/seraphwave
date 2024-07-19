package com.seraphwave.config;

import org.bukkit.configuration.file.FileConfiguration

class Config(
    val audioSettings: AudioSettings,
    val metaInfo: ServerMetaInfo,
    val httpServer: ServerConfig
)

class ServerConfig(
    val host: String,
    val port: Int,
    val https: Boolean
)
class ServerMetaInfo(
  val welcomeMsg: String,
  val webSocketUrl: String,
)
class AudioSettings(
  val activationRadius: Double,
)

def fromFileConfig(config: FileConfiguration): Config = {
  Config(
    audioSettings = AudioSettings(
      activationRadius = config.getDouble("audio-settings.activation-radius", 30.0)
    ),
    metaInfo = ServerMetaInfo(
      welcomeMsg = config.getString("meta-info.welcome-msg", "Welcome to proximity chat!"),
      webSocketUrl = config.getString("http-server.websocket-url", "auto")
    ),
    httpServer = ServerConfig(
      host = config.getString("http-server.host", "localhost"),
      port = config.getInt("http-server.port", 65437),
      https = config.getBoolean("http-server.https", true)
    )
  )
}
