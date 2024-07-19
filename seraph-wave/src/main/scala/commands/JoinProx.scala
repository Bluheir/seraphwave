package com.seraphwave.commands

import org.bukkit.command._
import org.bukkit.entity.Player
import cats.effect.unsafe.IORuntime
import cats.effect.IO
import org.bukkit.ChatColor
import org.bukkit.NamespacedKey
import com.seraphwave._
import org.bukkit.persistence.PersistentDataType

import cats.effect.std.{SecureRandom}
import com.seraphwave.data.SessionInfo
import com.seraphwave.server.CodeRemoval
import com.seraphwave.server.SQueue

implicit val runtime: IORuntime = cats.effect.unsafe.IORuntime.global

class JoinProx extends CommandExecutor {

  def randomCode(): IO[Array[Byte]] = {
    for {
      rand <- SecureRandom.javaSecuritySecureRandom[IO]
      bytes <- rand.nextBytes(32)
    } yield (bytes)
  }

  def storeCode(player: Player, queue: SQueue): IO[Unit] = {
    val key = NamespacedKey(Plugin.instance, "vc-access-code")

    for {
      container <- IO(player.getPersistentDataContainer())
      randomCode <- randomCode()
      sessionInfo <- IO(
        SessionInfo(
          sessionCode = randomCode,
          uuid = player.getUniqueId(),
          username = player.getName()
        )
      )
      // tell the websocket server that the code was consumed.
      _ <- queue.offer(sessionInfo)
      _ <- IO({
        container.set(key, PersistentDataType.BYTE_ARRAY, randomCode)
        player.sendMessage("You have joined proximity chat!")
      })
    } yield (())
  }
  def onCommandPure(
      sender: CommandSender,
      command: Command,
      label: String,
      args: Array[String]
  ): IO[Boolean] = {
    sender match {
      case player: Player =>
        args.headOption match {
          case Some(code) =>
            for {
              connected <- Plugin.httpInstance.isConnected(player.getUniqueId())
              _ <- connected match {
                case true =>
                  IO(
                    player.sendMessage(
                      s"${ChatColor.RED}You cannot consume a code while connected to proximity chat."
                    )
                  )
                case false =>
                  for {
                    removeCode <- Plugin.httpInstance.removeCode(code)
                    _ <- removeCode match {
                      case CodeRemoval.Success(queue) =>
                        storeCode(player, queue)
                      case CodeRemoval.NotExists =>
                        IO(
                          player.sendMessage(
                            s"${ChatColor.RED}That code does not exist!"
                          )
                        )
                      case CodeRemoval.NotConnected =>
                        IO(
                          player.sendMessage(
                            s"${ChatColor.RED}Do not close the browser tab between generating the code and invoking this command."
                          )
                        )
                    }
                  } yield ()
              }
            } yield true
          case None =>
            IO.pure(false)
        }
      case _ =>
        IO(
          sender.sendMessage(
            s"${ChatColor.RED}Only a player can execute this command."
          )
        ).as(true)
    }
  }
  override def onCommand(
      sender: CommandSender,
      command: Command,
      label: String,
      args: Array[String]
  ): Boolean = {
    onCommandPure(sender, command, label, args).unsafeRunSync()
  }
}
