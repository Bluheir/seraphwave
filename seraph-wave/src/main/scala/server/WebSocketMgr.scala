package com.seraphwave.server

import com.seraphwave.config._
import com.seraphwave.utils._
import com.seraphwave.data._
import com.seraphwave._

import scala.jdk.CollectionConverters.IteratorHasAsScala

import cats.effect.IO
import cats.effect.std.MapRef
import cats.effect.std.Queue

import fs2.{Pull, Pipe, Stream, Chunk}

import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.{
  Text as TextFrame,
  Binary as BinaryFrame
}

import io.circe.parser._

import java.util.{UUID, List as JList}
import java.util.concurrent.Callable

import org.bukkit.entity.{Player, Entity}
import org.bukkit.Bukkit

import scodec.bits.ByteVector
import cats.effect.kernel.Outcome.Canceled
import cats.effect.kernel.Outcome.Succeeded
import cats.instances.queue

type SQueue = Queue[IO, SessionInfo]
enum CodeRemoval:
  case Success(queue: SQueue)
  case NotExists
  case NotConnected

enum ClientState:
  case Offline(queue: Queue[IO, Boolean])
  case Online(queue: Queue[IO, OnlineFrame])

enum OnlineFrame:
  case Binary(val frame: BinaryFrame)
  case Offline

class WebSocketMgr(
    private val codesMap: MapRef[IO, String, Option[Option[SQueue]]],
    private val clientMap: MapRef[IO, UUID, Option[ClientState]],
    val config: Config
) {

  /** Determines if a player with the given UUID is connected.
    *
    * @param uuid
    *   The UUID of the player.
    * @return
    */
  def isConnected(uuid: UUID): IO[Boolean] = for {
    v <- this.clientMap(uuid).get
  } yield (v.isDefined)

  /** Removes a temporary code, and returns the success value.
    *
    * @param code
    *   The code to remove.
    * @return
    */
  def removeCode(code: String): IO[CodeRemoval] = {
    val value_ref = codesMap(code)
    for {
      status <- value_ref.get.map {
        // the temp code does exist, and a client is willing to consume that code
        case Some(Some(value)) => CodeRemoval.Success(value)
        // the temp code does exist, but a client that would have used the code did not connect
        case Some(None) => CodeRemoval.NotConnected
        // the temp code does not exist
        case None => CodeRemoval.NotExists
      }
      _ <- codesMap.unsetKey(code)
    } yield (status)
  }

  /** Sends a rotation update to the client with the given UUID.
    *
    * @param uuid
    *   The UUID of the player.
    * @param rotation
    *   The unit vector representing the new direction the player is facing.
    * @return
    */
  def rotationUpdate(uuid: UUID, rotation: Vec3d): IO[Unit] = {
    for {
      value <- this.clientMap(uuid).get
      _ <- value match {
        case Some(ClientState.Online(value)) =>
          for {
            _ <- value.offer(
              OnlineFrame.Binary(BinaryFrame(serRotationUpdate(rotation)))
            )
          } yield ()
        case _ => IO.pure(())
      }
    } yield ()
  }

  /** Sends an update to the client telling if a player is online/offline.
    *
    * @param uuid
    *   The UUID of the player.
    * @param status
    *   The new connection status of the player: online or offline.
    * @return
    */
  def updateConnStatus(uuid: UUID, status: PlayerConnStatus): IO[Unit] = {
    status match {
      case PlayerConnStatus.Online =>
        for {
          stateUuid <- clientMap(uuid).get

          _ <- stateUuid match {
            case Some(ClientState.Online(_)) => IO.pure(())
            // notify that the client is online now
            case Some(ClientState.Offline(queue)) => queue.offer(true)
            case None                             => IO.pure(())
          }
        } yield (())
      case PlayerConnStatus.Offline =>
        for {
          stateUuid <- clientMap(uuid).get

          _ <- stateUuid match {
            case Some(ClientState.Offline(_)) => IO.pure(())
            // notify that the client is offline now
            case Some(ClientState.Online(queue)) =>
              queue.offer(OnlineFrame.Offline)
            case None => IO.pure(())
          }
        } yield (())
    }
  }
  val wsHandler: Pipe[IO, WebSocketFrame, WebSocketFrame] = in => {
    in.pull.uncons1
      .flatMap(value => {
        value match {
          case Some((frame: TextFrame, rem)) =>
            decode[HelloObj](frame.str) match {
              case Right(value) => handleHello(hello = value, rem = rem)
              case Left(_)      => Pull.done
            }
          case _ => Pull.done
        }
      })
      .stream
  }

  private def toSendPull(
      queue: Queue[IO, OnlineFrame],
      stopQueue: Queue[IO, Unit]
  ): Pull[IO, WebSocketFrame, Unit] = {
    Pull.eval(queue.take).flatMap {
      case OnlineFrame.Binary(frame) =>
        Pull.output1(frame) >> toSendPull(queue, stopQueue)
      case OnlineFrame.Offline => Pull.eval(stopQueue.offer(()))
    }
  }
  private val radius2 = config.audioSettings.activationRadius * 2

  /** Sends a packet containing the relative position of the player to the
    * speaker, the direction of the speaker, and the audio bytes to all players
    * within the given speaker's activation radius.
    *
    * @param speaker
    *   The Bukkit `Player` object corresponding to the player who is speaking.
    * @param speakerUuid
    *   The UUID of the player who is speaking.
    * @param frame
    *   The audio bytes the player is sending.
    * @return
    */
  private def speak(
      speaker: Player,
      speakerUuid: UUID,
      frame: ByteVector
  ): IO[Unit] =
    for {
      tuple <- IO({
        val loc = speaker.getLocation()
        val entities = Bukkit
          .getScheduler()
          .callSyncMethod(
            Plugin.instance,
            new Callable[JList[Entity]] {
              override def call: JList[Entity] =
                speaker.getNearbyEntities(radius2, radius2, radius2)
            }
          )
          .get()

        (
          entities.iterator().asScala.toSeq,
          Vec3d.fromSpigot(loc.toVector()),
          Vec3d.fromSpigot(loc.getDirection())
        )
      })
      entities <- IO.pure(tuple._1)
      // the position of the speaker
      speakerPos <- IO.pure(tuple._2)
      // direction the speaker is facing
      speakerDir <- IO.pure(tuple._3)

      _ <- entities.foldLeft(IO.pure(()))((m, entity) =>
        for {
          _ <- m
          _ <- entity match {
            case near: Player =>
              for {
                opt <- IO({
                  val loc = near.getLocation()
                  val nearPos = Vec3d.fromSpigot(loc.toVector())
                  val nearDir = Vec3d.fromSpigot(loc.getDirection())

                  val relativePos = speakerPos - nearPos
                  val nearUuid = near.getUniqueId()

                  if (
                    near.getUniqueId == speakerUuid ||
                    relativePos.mag > config.audioSettings.activationRadius
                  ) {
                    None
                  } else {
                    Some((nearUuid, nearPos, nearDir, relativePos))
                  }
                })

                _ <- opt match {
                  case Some((nearUuid, nearPos, nearDir, relativePos)) =>
                    for {
                      clientVal <- clientMap(nearUuid).get
                      _ <- clientVal match {
                        case Some(ClientState.Online(queue)) =>
                          for {
                            _ <- queue.offer(
                              OnlineFrame.Binary(
                                BinaryFrame(
                                  serPosDir(
                                    relativePos,
                                    speakerDir,
                                    speakerUuid,
                                    frame
                                  )
                                )
                              )
                            )
                          } yield ()
                        case _ => IO.pure(())
                      }
                    } yield ()
                  case None => IO.pure(())
                }
              } yield ()
            case _ => IO.pure(())
          }
        } yield ()
      )
    } yield ()

  private def onReceivePull(
      player: Player,
      uuid: UUID,
      rem: Stream[IO, WebSocketFrame | Unit]
  ): Pull[IO, WebSocketFrame, Unit] = {
    rem.pull.uncons1.flatMap {
      case Some(frame: BinaryFrame, rem) =>
        Pull.eval(speak(player, uuid, frame.data)) >>
          onReceivePull(player, uuid, rem)
      case Some((_: Unit, rem)) =>
        Pull.output1(
          TextFrame(
            playerUpdateJson(PlayerConnStatus.Offline.toUpdate()).toString
          )
        ) >>
          waitUntilOnlineF(
            player.getUniqueId(),
            rem.map {
              case value: WebSocketFrame => value
              case _: Unit               => sys.error("unreachable")
            }
          )
      case _ => Pull.eval(clientMap.unsetKey(uuid))
    }
  }

  private def handlePlayer(
      player: Player,
      queue: Queue[IO, OnlineFrame],
      rem: Stream[IO, WebSocketFrame]
  ): Pull[IO, WebSocketFrame, Unit] = {
    Pull
      .eval(for {
        stopQueue <- Queue.bounded[IO, Unit](1)
        uuid <- IO(player.getUniqueId())
      } yield (stopQueue, uuid))
      .flatMap { case (stopQueue, uuid) =>
        onReceivePull(
          player,
          uuid,
          rem.merge(Stream.fromQueueUnterminated(stopQueue))
        ).stream
          .merge(toSendPull(queue, stopQueue).stream)
          .pull
          .echo
      }
  }

  private def waitUntilOnline(
      uuid: UUID,
      queue: Queue[IO, Boolean]
  ): IO[Option[(TextFrame, Player, Queue[IO, OnlineFrame])]] = for {
    // wait until the player is online using the queue
    resultant <- queue.take
    result <- resultant match {
      case true => for {
        queue <- Queue.bounded[IO, OnlineFrame](4096)
        _ <- clientMap.setKeyValue(uuid, ClientState.Online(queue))
        player <- IO(Plugin.instance.getServer().getPlayer(uuid))
        tf <- IO.pure(
          TextFrame(
            playerUpdateJson(
              PlayerUpdate.PlayerConnect(PlayerConnStatus.Online)
            ).toString
          )
        )
      } yield Some((tf, player, queue))
      case false => IO.pure(None)
    }

  } yield result

  private def waitUntilOnlineF(
      uuid: UUID,
      rem: Stream[IO, WebSocketFrame]
  ): Pull[IO, WebSocketFrame, Unit] =
    (for {
      queue <- Pull.eval(for {
        queue <- Queue.bounded[IO, Boolean](1)
        _ <- clientMap.setKeyValue(uuid, ClientState.Offline(queue))
      } yield queue)
      p1 = Pull.eval(waitUntilOnline(uuid, queue)).flatMap {
        case Some((tf, player, queue)) => Pull.output1(tf) >> handlePlayer(player, queue, rem)
        case None => Pull.eval(clientMap.unsetKey(uuid))
      }
      p2 = rem.pull.peek1 >> Pull.eval(queue.offer(false))
    } yield p1.stream.merge(p2.stream).pull.echo).flatMap(v => v)

  private def tempCode(
      code: String,
      rem: Stream[IO, WebSocketFrame]
  ): Pull[IO, WebSocketFrame, Unit] = Pull
    .eval(for {
      codeVal <- codesMap(code).get
      response <- codeVal match {
        case Some(value) =>
          value match {
            case None => {
              for {
                queue <- Queue.bounded[IO, SessionInfo](1)
                _ <- codesMap.setKeyValue(code, Some(queue))
                // wait for the player to execute the command /joinprox <code>
                info <- queue.take
                // this temporary code can be discarded
                _ <- codesMap.unsetKey(code)
                queue <- Queue.bounded[IO, OnlineFrame](4096)
                _ <- clientMap.setKeyValue(
                  info.uuid,
                  ClientState.Online(queue)
                )
                player <- IO(
                  Plugin.instance.getServer().getPlayer(info.uuid)
                )
              } yield (Right((info, player, queue)))
            }
            case Some(_) => {
              IO.pure(
                Left(
                  TextFrame(responseErrorJson(errCodeConsumed).toString())
                )
              )
            }
          }
        case None => {
          IO.pure(
            Left(TextFrame(responseErrorJson(errTempNotExists).toString()))
          )
        }
      }
    } yield response)
    .flatMap {
      case Right((info, player, queue)) =>
        Pull.output(
          Chunk(
            TextFrame(sessionInfoJson(info).toString),
            TextFrame(
              playerUpdateJson(PlayerConnStatus.Online.toUpdate()).toString
            )
          )
        ) >> handlePlayer(player, queue, rem)
      case Left(value) => Pull.output1(value)
    }
  private def fullCode(
      sessionCode: Array[Byte],
      uuid: UUID,
      rem: Stream[IO, WebSocketFrame]
  ): Pull[IO, WebSocketFrame, Unit] = Pull
    .eval(for {
      player <- IO(Plugin.instance.getServer().getOfflinePlayer(uuid))
      contained <- Plugin.codeMgr.contains(uuid, sessionCode)
      result <- player match {
        case onlinePlayer: Player if contained =>
          for {
            queue <- Queue.bounded[IO, OnlineFrame](4096)
          } yield Right(Right((onlinePlayer, uuid, queue)))
        case _ if contained => {
          val tf = TextFrame(
            playerUpdateJson(PlayerConnStatus.Offline.toUpdate())
              .toString()
          )
          IO.pure(Right(Left((tf, uuid))))
        }
        case _ =>
          IO.pure(
            Left(TextFrame(responseErrorJson(errSessionKey).toString))
          )
      }
    } yield result)
    .flatMap {
      case Right(Right((player, uuid, queue))) =>
        handlePlayerCheckConn(
          uuid,
          Pull.eval(
            clientMap.setKeyValue(
              uuid,
              ClientState.Online(queue)
            )
          ) >>
            Pull.output1(
              TextFrame(
                playerUpdateJson(
                  PlayerConnStatus.Online.toUpdate()
                ).toString
              )
            ) >>
            handlePlayer(player, queue, rem)
        )
      case Right(Left((tf, uuid))) =>
        handlePlayerCheckConn(
          uuid,
          Pull.output1(tf) >> waitUntilOnlineF(uuid, rem)
        )
      case Left(tf) => Pull.output1(tf)
    }

  private def handleHello(
      hello: HelloObj,
      rem: Stream[IO, WebSocketFrame]
  ): Pull[IO, WebSocketFrame, Unit] = {
    // Invalid protocol version
    if (hello.protocolV != 0) {
      return Pull.done
    }

    hello.obj match {
      case HelloMsgData.TempCode(code) => this.tempCode(code, rem)
      case HelloMsgData.FullCode(sessionCode, uuid) =>
        this.fullCode(sessionCode, uuid, rem)
    }
  }

  private def handlePlayerCheckConn(
      uuid: UUID,
      afterPull: Pull[IO, WebSocketFrame, Unit]
  ): Pull[IO, WebSocketFrame, Unit] =
    Pull.eval(isConnected(uuid)).flatMap {
      case true =>
        Pull.output1(TextFrame(responseErrorJson(errCodeConsumed).toString()))
      case false => afterPull
    }
}
