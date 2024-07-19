package com.seraphwave.config

import com.seraphwave.pluginInstance

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.{
  JcaX509CertificateConverter,
  JcaX509v3CertificateBuilder
}
import org.bouncycastle.cert.{X509v3CertificateBuilder}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.{JcaContentSignerBuilder}
import org.bouncycastle.operator.{ContentSigner}

import java.math.BigInteger
import java.util.Base64
import java.util.Date

import java.security._
import java.security.cert.X509Certificate
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Paths
import java.nio.charset.StandardCharsets

import cats.effect.IO
import cats.effect.kernel.Resource

object CertUtils {
  Security.addProvider(BouncyCastleProvider())

  private def genKeyPair(): KeyPair =
    val keyPairGen = KeyPairGenerator.getInstance("RSA")
    keyPairGen.initialize(2048)
    keyPairGen.genKeyPair()

  private def genSelfSigned(keyPair: KeyPair, domain: String): X509Certificate =
    val now = System.currentTimeMillis()
    val startDate = new Date(now)
    // expires in one year
    val endDate = new Date(now + 365 * 24 * 3600 * 1000L)

    val serial = BigInteger.valueOf(now)

    val dn = X500Name(s"CN=${domain}")
    val contentSigner: ContentSigner = JcaContentSignerBuilder(
      "SHA256WithRSAEncryption"
    ).build(keyPair.getPrivate)

    val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
      dn,
      serial,
      startDate,
      endDate,
      dn,
      keyPair.getPublic
    )

    val certHolder = certBuilder.build(contentSigner)
    JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder)

  private def outputStream(file: File): Resource[IO, FileOutputStream] =
    Resource.make {
      IO.blocking(FileOutputStream(file))
    } { outStream =>
      IO.blocking(outStream.close()).handleErrorWith(_ => IO.unit)
    }
  private def inputStream(file: File): Resource[IO, FileInputStream] =
    Resource.make {
      IO.blocking(FileInputStream(file))
    } { inStream =>
      IO.blocking(inStream.close()).handleErrorWith(_ => IO.unit)
    }

  private def createNewCert(
      keyFile: File,
      certFile: File
  ): IO[(PrivateKey, X509Certificate)] =
    val keyOut = outputStream(keyFile)
    val certOut = outputStream(certFile)

    val keyCert = IO({
      val keyPair = genKeyPair()
      val cert = genSelfSigned(keyPair, "seraphwave")

      (keyPair.getPrivate(), cert)
    })

    for {
      keyCert <- keyCert
      keyPem <- IO.pure({
        val pemEncoded =
          s"-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder.encodeToString(keyCert._1.getEncoded)}\n-----END PRIVATE KEY-----"

        pemEncoded.getBytes(StandardCharsets.UTF_8)
      })
      _ <- keyOut.use { outStream =>
        IO.blocking(outStream.write(keyPem))
      }
      _ <- certOut.use { outStream =>
        IO.blocking(outStream.write(keyCert._2.getEncoded))
      }
    } yield keyCert
  def readExistingCert(
      keyFile: File,
      certFile: File
  ): IO[(PrivateKey, X509Certificate)] =
    val keyIn = inputStream(keyFile)
    val certIn = inputStream(certFile)

    for {
      keyBytes <- keyIn.use { inStream =>
        IO.blocking(
          Base64.getDecoder.decode(String(inStream.readAllBytes(), StandardCharsets.UTF_8)
            .split("\\n")(1)
          )
        )
      }
      cert <- certIn.use { inStream =>
        IO.blocking({
          val certFactory = CertificateFactory.getInstance("X.509")
          certFactory
            .generateCertificate(inStream)
            .asInstanceOf[X509Certificate]
        })
      }
      keyCert <- IO({
        val keySpec = new PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(keySpec).asInstanceOf[RSAPrivateKey]
        (privateKey, cert)
      })
    } yield keyCert

  def getOrCreateCert(): IO[SSLContext] = for {
    nextEffect <- IO({
      val dataFolder = pluginInstance().getDataFolder()
      dataFolder.mkdir()

      val folderPath = dataFolder.getPath()

      val keyPath = Paths.get(folderPath, "key.pem")
      val certPath = Paths.get(folderPath, "cert.der")

      val keyFile = keyPath.toFile()
      val certFile = certPath.toFile()

      if(keyFile.createNewFile() | certFile.createNewFile()) {
        createNewCert(keyFile, certFile)
      } else {
        readExistingCert(keyFile, certFile)
      }
    })
    keyCert <- nextEffect
    sslContext <- IO({
    val keyStore = KeyStore.getInstance("PKCS12")
      keyStore.load(null, null)
      keyStore.setCertificateEntry("certificate", keyCert._2)
      keyStore.setKeyEntry("private-key", keyCert._1, Array.empty, Array(keyCert._2))

      val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      keyManagerFactory.init(keyStore, Array.empty)

      // Create SSLContext
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(keyManagerFactory.getKeyManagers, null, null)

      sslContext
    })
  } yield sslContext
}
