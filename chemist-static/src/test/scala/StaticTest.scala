package funnel
package chemist
package static

import org.scalatest._
import java.io.File
import knobs.{FileResource,ClassPathResource,Required}
import scalaz.concurrent.Task

class StaticTest extends FlatSpec with Matchers {
  it should "load Instances from chemist.cfg" in {
    val instances = (for {
      cfg   <- (knobs.load(Required(
        ClassPathResource("oncue/chemist.cfg")) :: Nil))
      sub   <- cfg.base.at("chemist.instances")
      ins   = Config.readInstances(sub)
                     } yield ins).run

    val x: Boolean = instances.exists {
      case (TargetID("instance1"), targets) =>
        targets.size == 1 && targets.foldLeft(true)((b,t) => t.uri.getPort()==1234)
      case _          => false
    } &&
    instances.exists {
      case (TargetID("instance2"), targets) =>
        targets.size == 1 && targets.foldLeft(true)((b,t) => t.uri.getPort() == 5678)
      case _          => false
    } &&
    instances.exists {
      case (TargetID("instance3"), targets) =>
        targets.size == 1 && targets.foldLeft(true)((b,t) => t.uri.getPort() == 9012)
      case _          => false
    }
    x should be (true)
  }
}