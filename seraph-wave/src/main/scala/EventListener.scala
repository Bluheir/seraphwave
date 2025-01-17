package com.seraphwave
import com.seraphwave.data.PlayerConnStatus

import org.bukkit.event._
import org.bukkit.event.player._
import com.seraphwave.utils.Vec3d
import com.seraphwave.Plugin.httpInstance
import org.bukkit.event.world.WorldSaveEvent
import com.seraphwave.Plugin.codeMgr

class EventListener extends Listener {
  @EventHandler
  def onPlayerJoin(event: PlayerJoinEvent): Unit = {
    httpInstance
      .updateConnStatus(
        event.getPlayer().getUniqueId(),
        PlayerConnStatus.Online
      )
      .unsafeRunSync()
  }
  @EventHandler
  def onPlayerQuit(event: PlayerQuitEvent): Unit = {
    httpInstance
      .updateConnStatus(
        event.getPlayer().getUniqueId(),
        PlayerConnStatus.Offline
      )
      .unsafeRunSync()
  }
  @EventHandler
  def onPlayerMove(event: PlayerMoveEvent): Unit = {
    val player = event.getPlayer()
    httpInstance.rotationUpdate(player.getUniqueId(), Vec3d.fromSpigot(event.getTo().getDirection())).unsafeRunSync()
  }
  @EventHandler
  def onWorldSave(event: WorldSaveEvent): Unit = {
    codeMgr.save().unsafeRunAndForget()
  }
}
