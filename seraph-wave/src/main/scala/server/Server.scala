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
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2

import io.circe.literal._
import io.circe._

import java.util.UUID
import javax.net.ssl.SSLContext
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import fs2.io.net.tls.TLSContext

def metaJson(meta: ServerMetaInfo): Json =
  json"""{"welcomeMsg": ${meta.welcomeMsg}, "webSocketUrl": ${meta.webSocketUrl}}"""

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
      if(filePath.contains(".")) {
        val fileExtensions = List(".js", ".css", ".html", ".png", ".svg", ".wasm")
        if (fileExtensions.exists(filePath.endsWith)) {
          static(filePath, request)
        } else {
          NotFound()
        }
      } else {
        static(filePath + ".html", request)
      }
    }
  }

  def app(ws: WebSocketBuilder2[IO]): Kleisli[IO, Request[IO], Response[IO]] =
    Router(
      "/" -> route(ws)
    ).orNotFound

  def startServer(sslContext: Option[SSLContext]) = sslContext match {
    case Some(value) => EmberServerBuilder.default[IO]
      .withHost(Host.fromString(config.httpServer.host).get)
      .withPort(Port.fromInt(config.httpServer.port).get)
      .withHttpWebSocketApp(ws => app(ws))
      .withTLS(TLSContext.Builder.forAsync[IO].fromSSLContext(value))
      .build
    case None => EmberServerBuilder.default[IO]
      .withHost(Host.fromString(config.httpServer.host).get)
      .withPort(Port.fromInt(config.httpServer.port).get)
      .withHttpWebSocketApp(ws => app(ws))
      .build
  }
}

object HttpServer {
  def newServer(config: Config): IO[HttpServer] = for {
    map <- MapRef.ofConcurrentHashMap[IO, String, Option[SQueue]]()
    clientMap <- MapRef.ofConcurrentHashMap[IO, UUID, ClientState]()

  } yield (HttpServer(codesMap = map, clientMap = clientMap, config))
}
