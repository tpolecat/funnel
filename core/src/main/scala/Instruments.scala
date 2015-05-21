package oncue.svc.funnel

import com.twitter.algebird.Group
import oncue.svc.funnel.{Buffers => B}
import java.net.URL
import java.util.concurrent.{ExecutorService,TimeUnit}
import scala.concurrent.duration._
import scalaz.concurrent.Task
import scalaz.stream.Process
import scalaz.stream.process1.lift

/**
 * Provider of counters, gauges, and timers, tied to some
 * `Monitoring` instance. Instruments returned by this class
 * may update multiple metrics, see `counter`, `gauge` and
 * `timer` methods for more information.
 */
class Instruments(val window: Duration,
                  val monitoring: Monitoring = Monitoring.default,
                  val bufferTime: Duration = 200 milliseconds) {

  private def nowL(s: String) = if (s == "") s else s"$s (now)"
  private def previousL(s: String) = if (s == "") s else s"$s ($window ago)"
  private def slidingL(s: String) = if (s == "") s else s"$s (past $window)"

  /**
   * Return a `PeriodicGauge` with the given starting value.
   * Keys updated by this `PeriodicGauge` are `now/label`,
   * `previous/label` and `sliding/label`.
   * See [[funnel.Periodic]].
   */
  def periodicGauge[O:Reportable:Group](
    label: String, units: Units[O] = Units.None, description: String = "", init: O): PeriodicGauge[O] = {
      val O = implicitly[Group[O]]
      val c = new PeriodicGauge[O] {
        val now = B.resetEvery(window)(B.accum[O,O](init)(O.plus))
        val prev = B.emitEvery(window)(now)
        val sliding = B.sliding(window)(identity[O])(O)
        val (nowK, incrNow) =
          monitoring.topic[O,O](
            s"now/$label", units, nowL(description))(now)
        val (prevK, incrPrev) =
          monitoring.topic[O,O](
            s"previous/$label", units, previousL(description))(prev)
        val (slidingK, incrSliding) =
          monitoring.topic[O,O](
            s"sliding/$label", units, slidingL(description))(sliding)
        def append(n: O): Unit = {
          incrNow(n); incrPrev(n); incrSliding(n)
        }
        def keys = Periodic[O](nowK, prevK, slidingK)
      }
      c.buffer(bufferTime) // only publish updates this often
  }

  /**
   * Return a `Counter` with the given starting count.
   * Keys updated by this `Counter` are `now/label`,
   * `previous/label` and `sliding/label`.
   * See [[funnel.Periodic]].
   * You should use counters only for metrics that are monotonic.
   */
  def counter(label: String,
              init: Int = 0,
              description: String = ""): Counter = {
    new Counter(periodicGauge[Double](label, Units.Count, description, init))
  }

  // todo: histogramgauge, histogramCount, histogramTimer
  // or maybe we just modify the existing combinators to
  // update some additional values

  /**
   * Records the elapsed time in the current period whenever the
   * returned `Gauge` is set. See `Elapsed.scala`.
   */
  private[funnel] def currentElapsed(label: String, desc: String): Gauge[Continuous[Double], Unit] = {
    val (k, snk) = monitoring.topic[Unit,Double](label, Units.Seconds, desc)(
      B.currentElapsed(window).map(_.toSeconds.toDouble))
    val g = new Gauge[Continuous[Double], Unit] {
      def set(u: Unit) = snk(u)
      def keys = Continuous(k)
    }
    g.set(())
    g

  }
  /**
   * Records the elapsed time in the current period whenever the
   * returned `Gauge` is set. See `Elapsed.scala`.
   */
  private[funnel] def currentRemaining(label: String,
                                       desc: String): Gauge[Continuous[Double], Unit] = {
    val (k, snk) = monitoring.topic[Unit,Double](label, Units.Seconds, desc)(
      B.currentRemaining(window).map(_.toSeconds.toDouble))
    val g = new Gauge[Continuous[Double], Unit] {
      def set(u: Unit) = snk(u)
      def keys = Continuous(k)
    }
    g.set(())
    g
  }

  /**
   * Records the elapsed time that the `Monitoring` instance has
   * been running whenver the returned `Gauge` is set. See `Elapsed.scala`.
   */
  private[funnel] def uptime(label: String): Gauge[Continuous[Double], Unit] = {
    val (k, snk) = monitoring.topic[Unit,Double](label, Units.Minutes,
      "Time elapsed since monitoring started")(
      B.elapsed.map(_.toSeconds.toDouble / 60))
    val g = new Gauge[Continuous[Double], Unit] {
      def set(u: Unit) = snk(u)
      def keys = Continuous(k)
    }
    g.set(())
    g
  }

  /**
   * Return a `Gauge` with the given starting value.
   * This gauge only updates the key `now/\$label`.
   * For a historical gauge that summarizes an entire
   * window of values as well, see `numericGauge`.
   */
  def gauge[A:Reportable](label: String, init: A,
                          units: Units[A] = Units.None,
                          description: String = ""): Gauge[Continuous[A],A] = {
    val g = new Gauge[Continuous[A],A] {
      val (key, snk) = monitoring.topic(s"now/$label", units, description)(B.resetEvery(window)(B.variable(init)))
      def set(a: A) = snk(_ => a)
      def keys = Continuous(key)

      set(init)
    }
    g.buffer(bufferTime)
  }

  /**
   * Return an `Edge` with the given initial `origin` and `destination`.
   * See [[Edge]] for more information.
   *
   * This will create the following instruments and keys:
   *
   * A string gauge  `now/$$label/origin`.
   * A string gauge  `now/$$label/destination`.
   * A timer         `?/$$label/timer` where `?` is `now`, `previous`, and `sliding`.
   * A traffic light `now/$$label/status`
   *
   * @param label The name of the traffic light metric
   * @param description A human-readable descirption of the semantics of this metric
   * @param origin The source of the request. Typically this should be the IP address of the calling host
   * @param destination The target of the outbound request; typically this is IP or DNS.
   */
  def edge(
    label: String,
    description: String = "",
    origin: String,
    destination: String): Edge =
      Edge(
        origin = gauge(
          label  = s"$label/origin",
          init   = origin),
        destination = gauge(
          label  = s"$label/destination",
          init   =  destination),
        timer = timer(
          label  = s"$label/timer"),
        status = trafficLight(
          label  = s"$label/status")
      )

  /**
   * Return a `TrafficLight` - a gauge whose value can be `Red`, `Amber`,
   * or `Green`. The initial value is `Red`. The key is `now/$$label`.
   *
   * @param label The name of the traffic light metric
   * @param description A human-readable descirption of the semantics of this metric
   */
  def trafficLight(label: String, description: String = ""): TrafficLight =
    TrafficLight(gauge(label, TrafficLight.Red, Units.TrafficLight, description))

  /**
   * Return a `Gauge` with the given starting value.
   * Unlike `gauge`, keys updated by this `Counter` are
   * `now/label`, `previous/label` and `sliding/label`.
   * See [[oncue.svc.funnel.Periodic]].
   */
  def numericGauge(label: String, init: Double,
                   units: Units[Stats] = Units.None,
                   description: String = ""): Gauge[Periodic[Stats],Double] = {
    val g = new Gauge[Periodic[Stats],Double] {
      val now = B.resetEvery(window)(B.stats)
      val prev = B.emitEvery(window)(now)
      val sliding = B.sliding(window)((d: Double) => Stats(d))(Stats.statsGroup)
      val (nowK, nowSnk) =
        monitoring.topic(s"now/$label", units, nowL(description))(now)
      val (prevK, prevSnk) =
        monitoring.topic(s"previous/$label", units, previousL(description))(prev)
      val (slidingK, slidingSnk) =
        monitoring.topic(s"sliding/$label", units, slidingL(description))(sliding)
      def keys = Periodic(nowK, prevK, slidingK)
      def set(d: Double): Unit = {
        nowSnk(d); prevSnk(d); slidingSnk(d)
      }
      set(init)
    }
    g.buffer(bufferTime)
  }

  /**
   * Return a `Timer` which updates the following keys:
   * `now/label`, `previous/label`, and `sliding/label`.
   * See [[oncue.svc.funnel.Periodic]].
   */
  def timer(label: String, description: String = ""): Timer[Periodic[Stats]] = {
    val t = new Timer[Periodic[Stats]] {
      val timer = B.resetEvery(window)(B.stats)
      val previousTimer = B.emitEvery(window)(timer)
      val slidingTimer = B.sliding(window)((d: Double) => Stats(d))(Stats.statsGroup)
      val u: Units[Stats] = Units.Duration(TimeUnit.MILLISECONDS)
      val (nowK, nowSnk) =
        monitoring.topic(s"now/$label", u, nowL(description))(timer)
      val (prevK, prevSnk) =
        monitoring.topic(s"previous/$label", u, previousL(description))(previousTimer)
      val (slidingK, slidingSnk) =
        monitoring.topic(s"sliding/$label", u, slidingL(description))(slidingTimer)
      def keys = Periodic(nowK, prevK, slidingK)
      def recordNanos(nanos: Long): Unit = {
        // record time in milliseconds
        val millis = nanos.toDouble / 1e6
        nowSnk(millis); prevSnk(millis); slidingSnk(millis)
      }
    }
    t.buffer(bufferTime)
  }

}
