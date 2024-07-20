package com.seraphwave.data;

import io.circe._
import io.circe.literal._
import java.util.UUID
import java.util.Base64

case class HelloObj(val protocolV: Int, val obj: HelloMsgData)

/** The message the client immediately sends to the server after connecting
  */
enum HelloMsgData:
  /** The client sends the temporary code and is awaiting a player to consume
    * that code in the client.
    *
    * @param code
    *   The temporary code that was sent.
    */
  case TempCode(val code: String)

  /** The client sends a session code and is waiting for the corresponding
    * player to go online.
    *
    * @param sessionCode
    *   The session code.
    * @param uuid
    *   The player UUID this session code corresponds to.
    */
  case FullCode(val sessionCode: Array[Byte], val uuid: UUID)

case class ResponseError(val code: Int, msg: String)

val errTempNotExists =
  ResponseError(code = 0, msg = "temporary code does not exist")
val errCodeConsumed = ResponseError(
  code = 1,
  msg = "code already consumed by another client"
)
val errSessionKey = ResponseError(code = 2, msg = "incorrect session key")

case class SessionInfo(
    val sessionCode: Array[Byte],
    val uuid: UUID,
    val username: String
)

/** If the player went offline/online.
  */
enum PlayerConnStatus:
  case Online
  case Offline

  def toUpdate(): PlayerUpdate = {
    PlayerUpdate.PlayerConnect(this)
  }

def playerConnJson(data: PlayerConnStatus): Json = {
  data match {
    case PlayerConnStatus.Online  => json""""online""""
    case PlayerConnStatus.Offline => json""""offline""""
  }
}

enum PlayerUpdate:
  case PlayerConnect(val conn: PlayerConnStatus)

def playerUpdateJson(data: PlayerUpdate): Json = {
  data match {
    case PlayerUpdate.PlayerConnect(conn) =>
      json"""{"updateType": "playerStatus", "value": ${playerConnJson(conn)}}"""
  }
}

def sessionInfoJson(data: SessionInfo): Json = {
  json"""
  {
    "code": ${Base64.getEncoder().encodeToString(data.sessionCode)},
    "uuid": ${data.uuid.toString()},
    "username": ${data.username}
  }
  """
}

def responseErrorJson(data: ResponseError): Json = {
  json"""
  {
    "errorCode": ${data.code},
    "msg": ${data.msg}
  }
  """
}

def decodeUuid(c: ACursor) = {
  for {
    uuid <- c.as[String]
    uuid <- decodeUUIDInner(uuid, c)
  } yield (uuid)
}
def decodeUUIDInner(uuid: String, c: ACursor) = {
  for {
    uuid <-
      try {
        Right(UUID.fromString(uuid))
      } catch {
        case _: IllegalArgumentException =>
          Left(
            DecodingFailure(
              DecodingFailure.Reason.CustomReason("uuid invalid"),
              c
            )
          )
    }
  } yield(uuid)
}

def decodeBase64(c: ACursor) = {
  for {
    base64str <- c.as[String]
    bytes <-
      try {
        Right(Base64.getDecoder().decode(base64str))
      } catch {
        case _: IllegalArgumentException =>
          Left(
            DecodingFailure(
              DecodingFailure.Reason.CustomReason("base64 invalid"),
              c
            )
          )
      }
  } yield (bytes)
}

implicit val decodeHelloObj: Decoder[HelloObj] = (c: HCursor) => {
  for {
    protocolV <- c.downField("protocolV").as[Int]
    obj <- decodeHelloMsgData(c)
  } yield (HelloObj(protocolV, obj))
}
implicit val decodeHelloMsgData: Decoder[HelloMsgData] = (c: HCursor) => {
  c.downField("type").as[String].flatMap {
    case "temp" =>
      for {
        code <- c.downField("code").as[String]
      } yield HelloMsgData.TempCode(code)
    case "full" =>
      for {
        sessionCode <- decodeBase64(c.downField("code"))
        uuid <- decodeUuid(c.downField("uuid"))
      } yield HelloMsgData.FullCode(
        sessionCode,
        uuid
      )
  }
}
