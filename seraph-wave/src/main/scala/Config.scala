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
  val altAccounts: Boolean,
  val welcomeMsg: String,
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
      altAccounts = config.getBoolean("meta-info.alt-accounts", true),
      welcomeMsg = config.getString("meta-info.welcome-msg", "Welcome to proximity chat!")
    ),
    httpServer = ServerConfig(
      host = config.getString("http-server.host", "localhost"),
      port = config.getInt("http-server.port", 65437)
    )
  )
}