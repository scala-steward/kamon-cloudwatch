package kamon.cloudwatch

import java.util.concurrent.atomic.AtomicReference

import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync

import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * Ship-and-forget. Let the future to process the actual shipment to Cloudwatch.
  */
private[cloudwatch] class MetricsShipper(configuration: Configuration) {
  import AmazonAsync._

  private val logger =
    LoggerFactory.getLogger(classOf[MetricsShipper].getPackage.getName)

  // Kamon 1.0+ requires to support hot-reconfiguration, which forces us to use an
  // AtomicReference here and hope for the best
  private val client: AtomicReference[AmazonCloudWatchAsync] =
    new AtomicReference(AmazonAsync.buildClient(configuration))

  def reconfigure(configuration: Configuration): Unit = {
    val oldClient = client.getAndSet(AmazonAsync.buildClient(configuration))
    if (oldClient != null) {
      disposeClient(oldClient)
    }
  }

  def shutdown(): Unit = {
    val oldClient = client.getAndSet(null)
    if (oldClient != null) {
      disposeClient(oldClient)
    }
  }

  def shipMetrics(nameSpace: String, datums: MetricDatumBatch)(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    implicit val currentClient: AmazonCloudWatchAsync = client.get
    logger.debug("Sending batch of {} metrics to CloudWatch", datums.size)
    datums
      .put(nameSpace)
      .map(result => logger.debug(s"Succeeded to push metric batch to Cloudwatch: $result"))
      .recover {
        case CloudWatchUnavailable =>
          logger.warn("Failed to send metric batch to Cloudwatch. Service temporarily unavailable.")
      }
  }

  private[this] def disposeClient(client: AmazonCloudWatchAsync): Unit =
    try {
      client.shutdown()
    } catch {
      case NonFatal(_) => // ignore exception
    }

}
