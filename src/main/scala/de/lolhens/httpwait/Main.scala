package de.lolhens.httpwait

import cats.data.OptionT
import cats.effect.{ExitCode, Resource}
import cats.syntax.option._
import ch.qos.logback.classic.{Level, Logger}
import fs2._
import monix.eval.{Task, TaskApp}
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.dsl.task._
import org.http4s.headers.Host
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.chaining._

object Main extends TaskApp {
  private val logger = LoggerFactory.getLogger(Main.getClass)

  private def setLogLevel(level: Level): Unit = {
    val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
    rootLogger.setLevel(level)
  }

  override def run(args: List[String]): Task[ExitCode] = Task.defer {
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
                     connectTimeout: Duration) {
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
      val connectTimeout: Duration = env.get("CONNECT_TIMEOUT")
        .map(Duration(_)).getOrElse(retryInterval)

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
    lazy val run: Task[Nothing] = Task.deferAction { scheduler =>
      BlazeServerBuilder[Task](scheduler)
        .bindHttp(options.port, options.host)
        .withHttpApp(service.orNotFound)
        .resource
        .use(_ => Task.never)
    }

    lazy val gatewayTimeout: Task[Response[Task]] = {
      (options.retryTimeout match {
        case finite: FiniteDuration =>
          Task.sleep(finite)

        case _ =>
          Task.never

      }) *> GatewayTimeout()
    }

    lazy val clientResource: Resource[Task, Client[Task]] =
      Resource.suspend(Task.deferAction(scheduler => Task {
        BlazeClientBuilder[Task](scheduler)
          .withConnectTimeout(options.connectTimeout)
          .resource
      }))

    lazy val service: HttpRoutes[Task] = HttpRoutes[Task] { request =>
      val requestTime = System.currentTimeMillis()

      for {
        uri <- OptionT.fromOption[Task] {
          request.uri.path.split("/").filter(_.nonEmpty).toList.some.collect {
            case (scheme@("http" | "https")) +: authorityString +: parts =>
              Uri.fromString(s"$scheme://${(authorityString +: parts).mkString("/")}").toTry.get
                .copy(query = request.uri.query, fragment = request.uri.fragment)
          }
        }
        response <- OptionT.liftF {
          val authority = uri.authority.get
          val hostHeader: Host = headers.Host(authority.host.value, authority.port)

          for {
            requestBytes <- request.as[Array[Byte]]
            newRequest = request.withUri(uri).putHeaders(hostHeader).withBodyStream(Stream.chunk(Chunk.bytes(requestBytes)))
            response <- clientResource.use { client =>
              logger.info(newRequest.toString)

              lazy val retryForCode: Task[Response[Task]] = {
                client.run(newRequest).use { response =>
                  logger.info(response.status.toString)

                  if (options.statusCodes.contains(response.status))
                    for {
                      responseBytes <- response.as[Array[Byte]]
                    } yield
                      response.withBodyStream(Stream.chunk(Chunk.bytes(responseBytes))).some
                  else
                    Task.now(none)
                }
                  .onErrorHandle { err =>
                    logger.error(err.getMessage, err)
                    none
                  }
                  .flatMap {
                    case Some(response) =>
                      Task.now(response)

                    case None =>
                      Task.sleep(options.retryInterval) *>
                        retryForCode
                  }
              }

              Task.race(
                gatewayTimeout,
                retryForCode
              ).map(_.merge)
            }
            _ = {
              val responseTime = System.currentTimeMillis()
              val duration = responseTime - requestTime
              logger.info(s"After $duration: $response")
            }
          } yield
            response
        }
      } yield
        response
    }
  }

}
