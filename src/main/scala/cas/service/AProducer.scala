package cas.service

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import cas.analysis.estimation._
import cas.analysis.subject.components.Description
import cas.utils.UtilAliases._
import cas.utils.StdImplicits.RightBiasedEither
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object AProducer {
  case object QueryTick
  case class ProducingError(msg: ErrorMsg)
}

/** Manages content flow btw dealer and router
  *
  * @param dealer concrete realization of ContentDealer
  * @param estimator accumulative estimator
  */
class AProducer(dealer: ContentDealer, estimator: TotalEstimator) extends Actor with ActorLogging { // TODO
  import AProducer._
  import ARouter._
  import cas.web.interface.ImplicitActorSystem._
  import system.dispatcher

  var querySchedule: Option[Cancellable] = None

  override def preStart = {
    super.preStart()
    val frequency = dealer.estimatedQueryFrequency
    querySchedule = Some(context.system.scheduler.schedule(frequency, frequency, self, QueryTick))
    log.info("[AContentService] preStart")
  }

  override def receive = serve(Nil, Nil)

  override def postStop = {
    super.postStop()
    log.info("[AContentService] postStop")
    querySchedule.foreach(_.cancel)
  }

  def serve(consumers: List[ActorRef], estimChunks: List[Estimations]): Receive = {

    case PullSubjects => changeContext(sender :: consumers, estimChunks)

    case PushingEstimations(chunk) => changeContext(consumers, chunk :: estimChunks)

    case QueryTick => {
      if (estimChunks.nonEmpty) estimChunks match {
        case Nil => Unit
        case chunk::chunks => chunk match {
          case Nil => changeContext(consumers, chunks)
          case e::es => dealer.pushEstimation(e) onComplete {
            case Success(Right(_)) => changeContext(consumers, es :: chunks)
            case Success(Left(err)) => log.error(err)
            case Failure(NonFatal(ex)) => logWarning(ex)
          }
        }
      }
      else consumers match {
        case Nil => Unit
        case c::cs => dealer.pullSubjectsChunk onComplete {
          case Success(Right(chunk)) => {
            c ! PulledSubjects(chunk)
            changeContext(cs, estimChunks)
          }
          case Success(Left(err)) => log.error(err)
          case Failure(NonFatal(ex)) => logWarning(ex)
        }
      }
    }

    case x => log.warning("Unexpected case type in content producer: " + x)
  }

  def logWarning(ex: Throwable) = log.warning(ex.getMessage)


  def changeContext(consumers: List[ActorRef], estims: List[Estimations]) = {
    if (estims.flatten.length > 25) {
      log.warning("estims length: " + estims.flatten.length)
    }
    // log.info(estims.flatten.mkString("Estims: ", ", ", "."))
    context.become(serve(consumers, estims))
  }

}