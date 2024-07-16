package com.seraphwave.utils

import org.bukkit.util.Vector as SVec3d
import java.util.UUID
import scodec.bits.ByteVector
import java.nio.ByteBuffer

class Vec3d(val x: Double, val y: Double, val z: Double) {
  def -(rhs: Vec3d): Vec3d = Vec3d(this.x - rhs.x, this.y - rhs.y, this.z - rhs.z)
  def /(value: Double): Vec3d = Vec3d(this.x / value, this.y / value, this.z / value)
  def abs: Double = Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z)
  def norm: Vec3d = this / this.abs
}
object Vec3d {
  def fromSpigot(vec: SVec3d): Vec3d = {
    Vec3d(vec.getX(), vec.getY(), vec.getZ())
  }
}
def serPosDir(pos: Vec3d, dir: Vec3d, uuid: UUID, bytes: ByteVector): ByteVector = {
  val byteBuffer = ByteBuffer
    .allocate(65 + bytes.length.toInt)
    .put(0.toByte)
    .putLong(uuid.getMostSignificantBits())
    .putLong(uuid.getLeastSignificantBits())
    .putDouble(pos.x)
    .putDouble(pos.y)
    .putDouble(pos.z)
    .putDouble(dir.x)
    .putDouble(dir.y)
    .putDouble(dir.z)
    .put(bytes.toByteBuffer)
    .rewind()

  ByteVector.view(byteBuffer)
}

def serRotationUpdate(dir: Vec3d): ByteVector = {
  val byteBuffer = ByteBuffer
    .allocate(25)
    .put(1.toByte)
    .putDouble(dir.x)
    .putDouble(dir.y)
    .putDouble(dir.z)
    .rewind()

  ByteVector.view(byteBuffer)
}
