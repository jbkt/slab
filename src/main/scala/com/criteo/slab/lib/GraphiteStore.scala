package com.criteo.slab.lib

import java.io._
import java.net._
import java.time._
import java.time.format.DateTimeFormatter

import com.criteo.slab.core._
import com.criteo.slab.utils
import com.criteo.slab.utils.{HttpClient, Jsonable}
import org.json4s.DefaultFormats

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class GraphiteStore(
                     host: String,
                     port: Int,
                     webHost: String,
                     checkInterval: Duration,
                     group: Option[String] = None,
                     serverTimeZone: ZoneId = ZoneId.systemDefault()
                   )(implicit ec: ExecutionContext) extends ValueStore {

  import GraphiteStore._

  private val jsonFormat = DefaultFormats ++ Jsonable[GraphiteMetric].serializers

  private val DateFormatter = DateTimeFormatter.ofPattern("HH:mm_YYYYMMdd").withZone(serverTimeZone)

  private val GroupPrefix = group.map(_ + ".").getOrElse("")

  override def upload(id: String, values: Metrical.Out): Future[Unit] = {
    utils.collectTries(values.toList.map { case (name, value) =>
      send(host, port, s"$GroupPrefix$id.$name", value)
    }) match {
      case Success(_) => Future.successful()
      case Failure(e) => Future.failed(e)
    }
  }

  override def fetch(id: String, context: Context): Future[Metrical.Out] = {
    val query = HttpClient.makeQuery(Map(
      "target" -> s"$GroupPrefix$id.*",
      "from" -> s"${DateFormatter.format(context.when)}",
      "until" -> s"${DateFormatter.format(context.when.plus(checkInterval))}",
      "format" -> "json"
    ))
    val url = new URL(s"$webHost/render$query")
    HttpClient.get[String](url, Map.empty).flatMap {
      content =>
        Jsonable.parse[List[GraphiteMetric]](content, jsonFormat) match {
          case Success(metrics) =>
            val pairs = transformMetrics(s"$GroupPrefix$id", metrics)
            if (pairs.isEmpty)
              Future.failed(MissingValueException(s"cannot fetch metric for $GroupPrefix$id"))
            else
              Future.successful(pairs)
          case Failure(e) => Future.failed(e)
        }
    }
  }

  override def fetchHistory(id: String, from: Instant, until: Instant): Future[Map[Long, Metrical.Out]] = {
    val query = HttpClient.makeQuery(Map(
      "target" -> s"$GroupPrefix$id.*",
      "from" -> s"${DateFormatter.format(from)}",
      "until" -> s"${DateFormatter.format(until)}",
      "format" -> "json",
      "noNullPoints" -> "true"
    ))
    val url = new URL(s"$webHost/render$query")
    HttpClient.get[String](url, Map.empty) flatMap { content =>
      Jsonable.parse[List[GraphiteMetric]](content, jsonFormat) map { metrics =>
        groupMetrics(s"$GroupPrefix$id", metrics)
      } match {
        case Success(metrics) => Future.successful(metrics)
        case Failure(e) => Future.failed(e)
      }
    }
  }
}

case class MissingValueException(message: String) extends Exception(message)

object GraphiteStore {
  def send(host: String, port: Int, target: String, value: Double): Try[Unit] = {
    Try {
      val socket = new Socket(InetAddress.getByName(host), port)
      val ps = new PrintStream(socket.getOutputStream)
      ps.println(s"$target $value ${Instant.now.toEpochMilli / 1000}")
      ps.flush
      socket.close
    }
  }

  // take first defined DataPoint of each metric
  def transformMetrics(prefix: String, metrics: List[GraphiteMetric]): Metrical.Out = {
    val pairs = metrics
      .map { metric =>
        metric.datapoints
          .find(_.value.isDefined)
          .map(dp => (metric.target.stripPrefix(s"${prefix}."), dp.value.get))
      }
      .flatten
      .toMap
    // returns metrics when all available or nothing
    if (pairs.size != metrics.size)
      Map.empty
    else
      pairs
  }

  // group metrics by timestamp
  def groupMetrics(prefix: String, metrics: List[GraphiteMetric]): Map[Long, Metrical.Out] = {
    metrics
      .view
      .flatMap { metric =>
        val name = metric.target.stripPrefix(s"${prefix}.")
        metric
          .datapoints
          .view
          .filter(_.value.isDefined)
          .map { dp =>
            (name, dp.value.get, dp.timestamp * 1000)
          }
          .force
      }
      .groupBy(_._3)
      .mapValues(_.map { case (name, value, _) => (name, value) }.toMap)
  }
}
