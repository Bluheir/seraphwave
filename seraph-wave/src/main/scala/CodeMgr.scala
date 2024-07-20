package com.seraphwave.data

import com.seraphwave.Plugin
import com.seraphwave.utils.{inputStream, outputStream}

import cats.effect.std.MapRef
import cats.effect.IO
import cats.effect.kernel.Ref

import java.util.UUID
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import java.util.Base64

import io.circe.HCursor
import io.circe.DecodingFailure.Reason
import io.circe.DecodingFailure
import io.circe.Decoder
import io.circe.parser.decode
import io.circe.literal.json


private type CodeMap = Map[UUID, Array[Byte]]

implicit val decodeCodeMap: Decoder[CodeMap] = (c: HCursor) => {
  c.keys match {
    case Some(keys) => {
      val first: Either[DecodingFailure, CodeMap] = Right(Map())
      keys.foldLeft(first)((accum, uuidStr) =>
        for {
          map <- accum
          uuid <- decodeUUIDInner(uuidStr, c)
          fieldValue <- decodeBase64(c.downField(uuidStr))
        } yield map + (uuid -> fieldValue)
      )
    }
    case None =>
      Left(DecodingFailure(Reason.WrongTypeExpectation("map", c.value), c))
  }
}

class CodeMgr(
    private val map: MapRef[IO, UUID, Option[Array[Byte]]],
    private val uuids: Ref[IO, Vector[UUID]]
):
  def load(): IO[Either[io.circe.Error, Unit]] = for {
    nextEffect <- IO({
      val dataFolder = Plugin.instance.getDataFolder().toString()
      val file = Paths.get(dataFolder, "sessioncodes.json").toFile()

      if (!file.exists()) {
        val toReturn: Either[DecodingFailure, CodeMap] = Right(Map())

        IO.pure(toReturn)
      } else {
        for {
          jsonStr <- inputStream(file).use(f =>
            IO.blocking(String(f.readAllBytes(), StandardCharsets.UTF_8))
          )
          jsonParse <- IO.pure(decode[CodeMap](jsonStr))
          _ <- jsonParse match {
            case Right(map) =>
              map.foldLeft(IO.pure(()))((accum, m) =>
                for {
                  _ <- accum
                  _ <- this.set(m._1, m._2)
                } yield ()
              )
            case Left(_) => IO.pure(())
          }
        } yield jsonParse
      }
    })
    jsonParse <- nextEffect

  } yield jsonParse.map(_ => ())

  def contains(uuid: UUID, code: Array[Byte]): IO[Boolean] = for {
    theCode <- map(uuid).get
  } yield theCode.map(_.sameElements(code)).getOrElse(false)

  def set(uuid: UUID, code: Array[Byte]): IO[Unit] = for {
    _ <- this.uuids.update(_ :+ uuid)
    _ <- this.map.setKeyValue(uuid, code)
  } yield ()

  def save(): IO[Unit] = for {
    keys <- this.uuids.get
    serMap <- keys.foldLeft(IO.pure(Map[String, String]()))((accum, uuid) =>
      for {
        accum <- accum
        value <- this.map(uuid).get
      } yield accum + (uuid.toString -> Base64.getEncoder.encodeToString(
        value.get
      ))
    )
    nextEffect <- IO({
      val serialized = json"""${serMap}""".toString
      val dataFolder = Plugin.instance.getDataFolder().toString()
      val file = Paths.get(dataFolder, "sessioncodes.json").toFile()

      file.createNewFile()

      outputStream(file).use(outStream => IO.blocking(outStream.write(serialized.getBytes(StandardCharsets.UTF_8))))
    })
    _ <- nextEffect
  } yield ()

object CodeMgr {
  def apply(): IO[CodeMgr] = for {
    map <- MapRef.ofConcurrentHashMap[IO, UUID, Array[Byte]]()
    uuids <- Ref[IO].of(Vector[UUID]())
  } yield new CodeMgr(map, uuids)
}
