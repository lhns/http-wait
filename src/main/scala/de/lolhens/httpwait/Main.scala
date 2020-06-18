package de.lolhens.httpwait

import cats.data.OptionT
import cats.effect.{ExitCode, Resource}
import cats.syntax.option._
import fs2._
import monix.eval.{Task, TaskApp}
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.jdkhttpclient.JdkHttpClient
import org.http4s.dsl.task._
import org.http4s.headers.Host
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.jdk.CollectionConverters._

object Main extends TaskApp {
  override def run(args: List[String]): Task[ExitCode] = Task.defer {
    val options = Options.fromEnv
    options.debug()
    println()
    new Server(options).run
  }

  case class Options(host: String,
                     port: Int,
                     statusCodes: List[Status],
                     timeout: Duration,
                     interval: FiniteDuration) {
    def debug(): Unit = {
      println(s"SERVER_HOST: $host")
      println(s"SERVER_PORT: $port")
      println(s"STATUS_CODES: ${statusCodes.map(_.code).mkString(",")}")
      println(s"CLIENT_TIMEOUT: $timeout")
      println(s"CLIENT_INTERVAL: $interval")
    }
  }

  object Options {
    def fromEnv: Options = {
      val env: Map[String, String] = System.getenv().asScala.toMap.map(e => (e._1, e._2.trim)).filter(_._2.nonEmpty)

      Options(
        host = env.getOrElse("SERVER_HOST", "0.0.0.0"),
        port = env.getOrElse("SERVER_PORT", "8080").toInt,
        statusCodes = env.getOrElse("STATUS_CODES", "200")
          .split("\\s*,\\s*").toList.filter(_.nonEmpty).map(e => Status.fromInt(e.toInt).toTry.get),
        timeout = Duration(env.getOrElse("CLIENT_TIMEOUT", "5min")),
        interval = Duration(env.getOrElse("CLIENT_INTERVAL", "5s")) match {
          case finite: FiniteDuration => finite
          case _ => throw new IllegalArgumentException("CLIENT_INTERVAL must be finite!")
        }
      )
    }
  }

  class Server(options: Options) {
    lazy val run: Task[Nothing] = Task.deferAction { implicit scheduler =>
      BlazeServerBuilder[Task]
        .bindHttp(options.port, options.host)
        .withHttpApp(service.orNotFound)
        .resource
        .use(_ => Task.never)
    }

    lazy val gatewayTimeout: Task[Response[Task]] = {
      (options.timeout match {
        case finite: FiniteDuration =>
          Task.sleep(finite)

        case _ =>
          Task.never

      }) *> GatewayTimeout()
    }

    lazy val clientResource: Resource[Task, Client[Task]] =
      Resource.liftF(JdkHttpClient.simple[Task].memoizeOnSuccess)

    lazy val service: HttpRoutes[Task] = HttpRoutes[Task] { request =>
      val uri = request.uri.path.split("/").filter(_.nonEmpty).toList match {
        case (scheme@("http" | "https")) +: authority +: parts =>
          Uri.fromString(s"$scheme://${(authority +: parts).mkString("/")}").toTry.get
            .copy(query = request.uri.query, fragment = request.uri.fragment)
      }
      val authority = uri.authority.get
      val hostHeader: Host = headers.Host(authority.host.value, authority.port)

      val response = for {
        requestBytes <- request.as[Array[Byte]]
        newRequest = request.withUri(uri).putHeaders(hostHeader).withBodyStream(Stream.chunk(Chunk.bytes(requestBytes)))
        response <- clientResource.use { client =>
          println(newRequest)

          lazy val retryForCode: Task[Response[Task]] = {
            client.run(newRequest).use { response =>
              println(response.status)

              if (options.statusCodes.contains(response.status))
                for {
                  responseBytes <- response.as[Array[Byte]]
                } yield
                  response.withBodyStream(Stream.chunk(Chunk.bytes(responseBytes))).some
              else
                Task.now(none)
            }
              .onErrorHandle { err =>
                err.printStackTrace()
                none
              }
              .flatMap {
                case Some(response) =>
                  Task.now(response)

                case None =>
                  Task.sleep(options.interval) *>
                    retryForCode
              }
          }

          Task.race(
            gatewayTimeout,
            retryForCode
          ).map(_.merge)
        }
      } yield
        response

      OptionT.liftF(response)
    }
  }

}
