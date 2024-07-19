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
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType

import scodec.bits.ByteVector

type SQueue = Queue[IO, SessionInfo]
enum CodeRemoval:
  case Success(queue: SQueue)
  case NotExists
  case NotConnected

enum ClientState:
  case Offline(queue: Queue[IO, Unit])
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
            case Some(ClientState.Offline(queue)) => queue.offer(())
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
      uuid: UUID
  ): IO[(TextFrame, Player, Queue[IO, OnlineFrame])] = {
    for {
      queue <- Queue.bounded[IO, Unit](1)
      _ <- clientMap.setKeyValue(uuid, ClientState.Offline(queue))
      // wait until the player is online using the queue
      _ <- queue.take
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
    } yield ((tf, player, queue))
  }

  private def waitUntilOnlineR(
      uuid: UUID,
      rem: Stream[IO, WebSocketFrame]
  ): Pull[IO, WebSocketFrame, Unit] = {
    Pull
      .eval(for {
        exist <- clientMap(uuid).get.map(_.isDefined)
        result <- exist match {
          case false => waitUntilOnline(uuid).map(value => Right(value))
          case true =>
            IO.pure(
              Left(
                TextFrame(responseErrorJson(errCodeConsumed).toString)
              )
            )
        }
      } yield (result))
      .flatMap {
        case Right((tf, player, queue)) =>
          Pull.output1(tf) >> handlePlayer(player, queue, rem)
        case Left(tf) => Pull.output1(tf)
      }
  }
  private def waitUntilOnlineF(
      uuid: UUID,
      rem: Stream[IO, WebSocketFrame]
  ): Pull[IO, WebSocketFrame, Unit] = {
    Pull
      .eval(waitUntilOnline(uuid))
      .flatMap((tf, player, queue) =>
        Pull.output1(tf) >> handlePlayer(player, queue, rem)
      )
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
      case HelloMsgData.TempCode(code) => {
        val effect = for {
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
        } yield (response)

        Pull.eval(effect).flatMap {
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
      }
      case HelloMsgData.FullCode(sessionCode, uuid) => {
        val effect = for {
          player <- IO(Option(Plugin.instance.getServer().getPlayer(uuid)))
          result <- player match {
            case Some(player) =>
              for {
                isOnline <- IO(player.isOnline())
                container <- IO(player.getPersistentDataContainer())
                key <- IO.pure(
                  NamespacedKey(Plugin.instance, "vc-access-code")
                )
                value <- IO(
                  Option(container.get(key, PersistentDataType.BYTE_ARRAY))
                    .getOrElse(Array[Byte]())
                )
                isCorrect <- IO.pure(value.sameElements(sessionCode))

                response <-
                  if (isOnline && isCorrect) {
                    for {
                      queue <- Queue.bounded[IO, OnlineFrame](4096)
                    } yield Right(Right((player, uuid, queue)))
                  } else if (isCorrect) {
                    val tf = TextFrame(
                      playerUpdateJson(PlayerConnStatus.Offline.toUpdate())
                        .toString()
                    )
                    IO.pure(Right(Left((tf, uuid))))
                  } else {
                    val tf = TextFrame(
                      responseErrorJson(errSessionKey).toString()
                    )
                    IO(Left(tf))
                  }
              } yield (response)
            case None => {
              IO.pure(
                Left(
                  TextFrame(responseErrorJson(errSessionKey).toString())
                )
              )
            }
          }
        } yield (result)

        Pull.eval(effect).flatMap {
          case Right(Right((player, uuid, queue))) =>
            Pull.output1(
              TextFrame(
                playerUpdateJson(PlayerConnStatus.Online.toUpdate()).toString
              )
            ) >> handlePlayerCheckOnline(player, uuid, queue, rem)
          case Right(Left((tf, uuid))) =>
            Pull.output1(tf) >> waitUntilOnlineR(uuid, rem)
          case Left(tf) => Pull.output1(tf)
        }
      }
    }
  }

  private def handlePlayerCheckOnline(
      player: Player,
      uuid: UUID,
      queue: Queue[IO, OnlineFrame],
      rem: Stream[IO, WebSocketFrame]
  ): Pull[IO, WebSocketFrame, Unit] =
    Pull.eval(isConnected(uuid)).flatMap {
      case true =>
        Pull.output1(TextFrame(responseErrorJson(errCodeConsumed).toString()))
      case false =>
        Pull.eval(
          clientMap.setKeyValue(
            uuid,
            ClientState.Online(queue)
          )
        ) >> handlePlayer(player, queue, rem)
    }
}
