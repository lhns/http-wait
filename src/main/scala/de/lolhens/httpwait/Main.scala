package de.lolhens.httpwait

import cats.data.OptionT
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.option._
import ch.qos.logback.classic.{Level, Logger}
import com.comcast.ip4s.{Host, Port}
import de.lolhens.http4s.proxy.Http4sProxy._
import fs2._
import org.http4s._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.log4s.getLogger

import java.net.http.HttpClient
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.chaining._

object Main extends IOApp {
  private val logger = getLogger

  private def setLogLevel(level: Level): Unit = {
    val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
    rootLogger.setLevel(level)
  }

  override def run(args: List[String]): IO[ExitCode] = IO.defer {
    val options = Options.fromEnv
    println(options.debug + "\n")
    setLogLevel(options.logLevel)
    new Server(options).run
  }

  case class Options(logLevel: Level,
                     host: String,
                     port: Int,
                     statusCodes: List[Status],
                     retryTimeout: Duration,
                     retryInterval: FiniteDuration,
                     connectTimeout: FiniteDuration) {
    def debug: String = {
      s"""LOG_LEVEL: $logLevel
         |SERVER_HOST: $host
         |SERVER_PORT: $port
         |STATUS_CODES: ${statusCodes.map(_.code).mkString(",")}
         |RETRY_TIMEOUT: $retryTimeout
         |RETRY_INTERVAL: $retryInterval
         |CONNECT_TIMEOUT: $connectTimeout""".stripMargin
    }
  }

  object Options {
    val default: Options = Options(
      logLevel = Level.INFO,
      host = "0.0.0.0",
      port = 8080,
      statusCodes = List(Status.Ok),
      retryTimeout = 5.minutes,
      retryInterval = 5.seconds,
      connectTimeout = 5.seconds
    )

    def fromEnv: Options = {
      val env: Map[String, String] = System.getenv().asScala.toMap.map(e => (e._1, e._2.trim)).filter(_._2.nonEmpty)

      def requireFinite(duration: Duration, name: String): FiniteDuration = duration match {
        case finite: FiniteDuration => finite
        case _ => throw new IllegalArgumentException(s"$name must be finite!")
      }

      val logLevel: Level = env.get("LOG_LEVEL").map(Level.valueOf).getOrElse(default.logLevel)
      val host: String = env.getOrElse("SERVER_HOST", default.host)
      val port: Int = env.get("SERVER_PORT").map(_.toInt).getOrElse(default.port)
      val statusCodes: List[Status] = env.get("STATUS_CODES")
        .map(_.split("\\s*,\\s*").toList.filter(_.nonEmpty).map(e => Status.fromInt(e.toInt).toTry.get))
        .getOrElse(default.statusCodes)
      val retryTimeout: Duration = env.get("RETRY_TIMEOUT").orElse(env.get("CLIENT_TIMEOUT"))
        .map(Duration(_)).getOrElse(default.retryTimeout)
      val retryInterval: FiniteDuration = env.get("RETRY_INTERVAL").orElse(env.get("CLIENT_INTERVAL"))
        .map(Duration(_).pipe(requireFinite(_, "RETRY_INTERVAL"))).getOrElse(default.retryInterval)
      val connectTimeout: FiniteDuration = env.get("CONNECT_TIMEOUT")
        .map(Duration(_).pipe(requireFinite(_, "CONNECT_TIMEOUT"))).getOrElse(retryInterval)

      Options(
        logLevel = logLevel,
        host = host,
        port = port,
        statusCodes = statusCodes,
        retryTimeout = retryTimeout,
        retryInterval = retryInterval,
        connectTimeout = connectTimeout
      )
    }
  }

  class Server(options: Options) {
    lazy val run: IO[Nothing] =
      (for {
        client <- clientResource
        _ <- EmberServerBuilder.default[IO]
          .withHost(Host.fromString(options.host).get)
          .withPort(Port.fromInt(options.port).get)
          .withHttpApp(service(client).orNotFound)
          .build
      } yield ())
        .use(_ => IO.never)

    lazy val clientResource: Resource[IO, Client[IO]] = JdkHttpClient[IO] {
      val builder = HttpClient.newBuilder()
      // workaround for https://github.com/http4s/http4s-jdk-http-client/issues/200
      if (Runtime.version().feature() == 11) {
        val params = javax.net.ssl.SSLContext.getDefault.getDefaultSSLParameters
        params.setProtocols(params.getProtocols.filter(_ != "TLSv1.3"))
        builder.sslParameters(params)
      }
      builder.connectTimeout(java.time.Duration.ofMillis(options.connectTimeout.toMillis))
      builder.build()
    }

    lazy val gatewayTimeout: IO[Response[IO]] = {
      (options.retryTimeout match {
        case finite: FiniteDuration =>
          IO.sleep(finite)

        case _ =>
          IO.never

      }) *> GatewayTimeout()
    }

    def service(client: Client[IO]): HttpRoutes[IO] = HttpRoutes[IO] { request =>
      val requestTime = System.currentTimeMillis()

      for {
        uri <- OptionT.fromOption[IO] {
          request.uri.path.renderString.split("/").filter(_.nonEmpty).toList.some.collect {
            case (scheme@("http" | "https")) +: authorityString +: parts =>
              Uri.fromString(s"$scheme://${(authorityString +: parts).mkString("/")}").toTry.get
                .copy(query = request.uri.query, fragment = request.uri.fragment)
          }
        }
        response <- OptionT.liftF {
          for {
            requestBytes <- request.as[Chunk[Byte]]
            newRequest = request
              .withDestination(uri)
              .withBodyStream(Stream.chunk(requestBytes))
            _ = logger.info(newRequest.toString)
            response <- {
              lazy val retryForCode: IO[Response[IO]] = {
                client.run(newRequest).use { response =>
                  logger.info(response.status.toString)

                  if (options.statusCodes.contains(response.status))
                    for {
                      responseBytes <- response.as[Chunk[Byte]]
                    } yield
                      response.withBodyStream(Stream.chunk(responseBytes)).some
                  else
                    IO(none)
                }
                  .handleError { err =>
                    logger.error(err)(err.getMessage)
                    none
                  }
                  .flatMap {
                    case Some(response) =>
                      IO(response)

                    case None =>
                      IO.sleep(options.retryInterval) >>
                        retryForCode
                  }
              }

              IO.race(
                gatewayTimeout,
                retryForCode
              ).map(_.merge)
            }
            _ = {
              val responseTime = System.currentTimeMillis()
              val duration = responseTime - requestTime
              logger.info(s"After ${duration}ms: $response")
            }
          } yield
            response
        }
      } yield
        response
    }
  }

}
