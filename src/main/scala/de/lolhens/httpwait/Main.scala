package de.lolhens.httpwait

import cats.data.OptionT
import cats.effect.{ExitCode, Resource}
import cats.syntax.option._
import fs2._
import javax.net.ssl.SSLContext
import monix.eval.{Task, TaskApp}
import monix.execution.Scheduler
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.dsl.task._
import org.http4s.headers.Host
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.jdk.CollectionConverters._

object Main extends TaskApp {
  val env: Map[String, String] = System.getenv().asScala.toMap.map(e => (e._1, e._2.trim)).filter(_._2.nonEmpty)

  private val host = env.getOrElse("SERVER_HOST", "0.0.0.0")
  private val port = env.getOrElse("SERVER_PORT", "8080").toInt
  private val statusCodes: List[Status] = env.getOrElse("STATUS_CODES", "200")
    .split("\\s,\\s").toList.filter(_.nonEmpty).map(e => Status.fromInt(e.toInt).toTry.get)
  private val timeout: Duration = Duration(env.getOrElse("CLIENT_TIMEOUT", "5min"))
  private val interval: FiniteDuration = Duration(env.getOrElse("CLIENT_INTERVAL", "1s")) match {
    case finite: FiniteDuration => finite
    case _ => throw new IllegalArgumentException("CLIENT_INTERVAL must be finite!")
  }

  override def run(args: List[String]): Task[ExitCode] = Task.deferAction { implicit scheduler =>
    BlazeServerBuilder[Task]
      .bindHttp(port, host)
      .withHttpApp(service.orNotFound)
      .resource
      .use(_ => Task.never)
  }

  val gatewayTimeout: Task[Response[Task]] = {
    (timeout match {
      case finite: FiniteDuration =>
        Task.sleep(finite)

      case _ =>
        Task.never

    }) *> GatewayTimeout()
  }

  val clientResource: Resource[Task, Client[Task]] =
    BlazeClientBuilder[Task](Scheduler.global)
      .withSslContext(SSLContext.getDefault)
      .resource

  def service: HttpRoutes[Task] = HttpRoutes[Task] { request =>
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

            if (statusCodes.contains(response.status))
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
                Task.sleep(interval) *>
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
