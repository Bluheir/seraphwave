package com.seraphwave.server;

import scala.jdk.CollectionConverters._

import com.seraphwave.config._
import com.seraphwave.data._
import com.seraphwave.pluginInstance
import com.seraphwave.utils._

import cats.effect.IO
import cats.data.Kleisli
import cats.effect.std.Random
import cats.effect.std.MapRef
import cats.effect.std.Queue

import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe._
import org.http4s.server.Router
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket._
import org.http4s.websocket.WebSocketFrame.{
  Text as TextFrame,
  Binary as BinaryFrame
}

import io.circe.literal._
import io.circe._
import io.circe.parser._

import fs2.{Pipe, Stream, Pull, Chunk}

import org.bukkit.entity.Player
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType

import java.util.UUID
import scodec.bits.ByteVector
import org.bukkit.Bukkit
import org.bukkit.entity
import java.{util => ju}

def metaJson(meta: ServerMetaInfo): Json =
  json"""{"welcomeMsg": ${meta.welcomeMsg}, "altAccounts": ${meta.altAccounts}, "webSocketUrl": ${meta.webSocketUrl}}"""

def codeJson(code: String): Json =
  json"""{"code": ${code}}"""

def randomCode(): IO[String] = {
  val alphanumerics = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"

  Random
    .scalaUtilRandom[IO]
    .flatMap(random =>
      Iterator
        .continually(())
        .take(3)
        .map(_ =>
          Iterator
            .continually(())
            .take(3)
            .map(_ =>
              random
                .betweenInt(0, alphanumerics.length)
                .map(alphanumerics.charAt)
            )
            .foldLeft(IO(""))((mstr, mc) =>
              for {
                str <- mstr
                c <- mc
              } yield (str + c)
            )
        )
        .foldLeft(IO(Vector[String]()))((marr, mstr) => {
          for {
            arr <- marr
            str <- mstr
          } yield (arr :+ str)
        })
        .map(_.mkString("-"))
    )
}

def static(file: String, request: Request[IO]) = {
  StaticFile
    .fromResource("svelte-build/" + file, Some(request))
    .getOrElseF(NotFound())
}

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

def newServer(config: Config): IO[HttpServer] = {
  for {
    map <- MapRef.ofConcurrentHashMap[IO, String, Option[SQueue]]()
    clientMap <- MapRef.ofConcurrentHashMap[IO, UUID, ClientState]()
  } yield (HttpServer(codesMap = map, clientMap = clientMap, config))
}

class HttpServer(
    private val codesMap: MapRef[IO, String, Option[Option[SQueue]]],
    private val clientMap: MapRef[IO, UUID, Option[ClientState]],
    val config: Config
) {
  def rotationUpdate(uuid: UUID, rotation: Vec3d): IO[Unit] = {
    for {
      value <- this.clientMap(uuid).get
      _ <- value match {
        case Some(ClientState.Online(value)) => for {
          _ <- value.offer(OnlineFrame.Binary(BinaryFrame(serRotationUpdate(rotation))))
        } yield ()
        case _ => IO.pure(())
      }
    } yield ()
  }
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
  private def speak(
      speaker: Player,
      speakerUuid: UUID,
      frame: ByteVector
  ): IO[Unit] = {
    for {
      tuple <- IO({
          val loc = speaker.getLocation()
          val entities = Bukkit
            .getScheduler()
            .callSyncMethod(
              pluginInstance(),
              new java.util.concurrent.Callable[ju.List[
                org.bukkit.entity.Entity
              ]] {
                override def call(): ju.List[entity.Entity] =
                  speaker.getNearbyEntities(radius2, radius2, radius2)
              }
            )
            .get()

          (
            entities.iterator().asScala.toSeq,
            Vec3d.fromSpigot(loc.toVector()),
            Vec3d.fromSpigot(loc.getDirection())
          )
        }
      )
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

                  val relativePos = nearPos - speakerPos
                  val nearUuid = near.getUniqueId()

                  if (
                    near.getUniqueId == speakerUuid ||
                    relativePos.abs > config.audioSettings.activationRadius
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
                                  serPosDir(relativePos, speakerDir, speakerUuid, frame)
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
  }
  private def onReceivePull(
      player: Player,
      uuid: UUID,
      rem: Stream[IO, WebSocketFrame | Unit]
  ): Pull[IO, WebSocketFrame, Unit] = {
    rem.pull.uncons1.flatMap {
      case Some(frame: BinaryFrame, rem) =>
        Pull
          .eval(speak(player, uuid, frame.data)) >> onReceivePull(
          player,
          uuid,
          rem
        )
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
              case _: Unit               => ???
            }
          )
      case None => Pull.eval(clientMap.unsetKey(uuid))
      case _    => Pull.done
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
      player <- IO(pluginInstance().getServer().getPlayer(uuid))
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
                    info <- queue.take
                    _ <- codesMap.unsetKey(code)
                    queue <- Queue.bounded[IO, OnlineFrame](4096)
                    _ <- clientMap.setKeyValue(
                      info.uuid,
                      ClientState.Online(queue)
                    )
                    player <- IO(
                      pluginInstance().getServer().getPlayer(info.uuid)
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
          player <- IO(Option(pluginInstance().getServer().getPlayer(uuid)))
          result <- player match {
            case Some(player) =>
              for {
                isOnline <- IO(player.isOnline())
                container <- IO(player.getPersistentDataContainer())
                key <- IO.pure(
                  NamespacedKey(pluginInstance(), "vc-access-code")
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
                      _ <- clientMap.setKeyValue(
                        uuid,
                        ClientState.Online(queue)
                      )
                    } yield Right(Right((player, queue)))
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
                  TextFrame(responseErrorJson(errPlayerNotExists).toString())
                )
              )
            }
          }
        } yield (result)

        Pull.eval(effect).flatMap {
          case Right(Right((player, queue))) =>
            Pull.output1(
              TextFrame(
                playerUpdateJson(PlayerConnStatus.Online.toUpdate()).toString
              )
            ) >> handlePlayer(player, queue, rem)
          case Right(Left((tf, uuid))) =>
            Pull.output1(tf) >> waitUntilOnlineR(uuid, rem)
          case Left(tf) => Pull.output1(tf)
        }
      }
    }
  }

  private val wsHandler: Pipe[IO, WebSocketFrame, WebSocketFrame] = in => {
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

  def removeCode(code: String): IO[CodeRemoval] = {
    val value_ref = codesMap(code)
    for {
      status <- value_ref.get.map {
        case Some(Some(value)) => CodeRemoval.Success(value)
        case Some(None)        => CodeRemoval.NotConnected
        case None              => CodeRemoval.NotExists
      }
      _ <- codesMap.unsetKey(code)
    } yield (status)
  }

  def route(ws: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case POST -> Root / "code" => {
      Ok(for {
        code <- randomCode()
        _ <- codesMap.setKeyValue(code, None)
      } yield (codeJson(code)))
    }
    case GET -> Root / "gateway" => {
      ws.build(wsHandler)
    }
    case GET -> Root / "meta"  => Ok(metaJson(config.metaInfo))
    case request @ GET -> Root => static("index.html", request)
    case request @ GET -> subDirs => {
      val filePath = subDirs.segments.mkString("/")

      if (
        List(".js", ".css", ".html", ".png", ".svg").exists(filePath.endsWith)
      ) {
        static(filePath, request)
      } else {
        NotFound()
      }
    }
  }

  def app(ws: WebSocketBuilder2[IO]): Kleisli[IO, Request[IO], Response[IO]] =
    Router(
      "/" -> route(ws)
    ).orNotFound

  def startServer() =
    BlazeServerBuilder[IO]
      .bindHttp(port = config.httpServer.port, host = config.httpServer.host)
      .withHttpWebSocketApp(ws => app(ws))
      .resource
}
