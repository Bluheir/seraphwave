package com.seraphwave.server

import com.seraphwave.config._

import cats.effect.IO
import cats.data.Kleisli
import cats.effect.std.Random
import cats.effect.std.MapRef

import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.circe._
import org.http4s.server.Router
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2

import io.circe.literal._
import io.circe._

import java.util.UUID

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

class HttpServer private (
    codesMap: MapRef[IO, String, Option[Option[SQueue]]],
    clientMap: MapRef[IO, UUID, Option[ClientState]],
    config: Config
) extends WebSocketMgr(codesMap, clientMap, config) {
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

object HttpServer {
  def newServer(config: Config): IO[HttpServer] = for {
    map <- MapRef.ofConcurrentHashMap[IO, String, Option[SQueue]]()
    clientMap <- MapRef.ofConcurrentHashMap[IO, UUID, ClientState]()

  } yield (HttpServer(codesMap = map, clientMap = clientMap, config))
}
