package cas.web.pages

import scala.io.Source
import scala.util.{Failure, Success, Try}
import spray.routing._
import spray.json._
import Directives._
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import cas.analysis.estimation.{LoyaltyConfigs, LoyaltyEstimator, TotalEstimator}
import cas.service.{AProducer$, AServiceControl}
import cas.utils._
import cas.web.dealers.DealersFactory
import cas.web.model._
import spray.json._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

object IndexPage {
  import cas.web.interface.ImplicitActorSystem._
  import scala.concurrent.ExecutionContext.Implicits.global
  import AServiceControl._
  import UsingDealerProtocol._
  implicit val timeout = Timeout(3.seconds)

	def apply(pagePath: String, serviceControl: ActorRef) = path(pagePath) {
    get {
      parameter("isRun".as[Boolean].?) { isRunOpt =>
        val contentServiceOpt = isRunOpt.map { isRun =>
          if (isRun) for {
            file <- Files.readFile(Files.currentDealer)
            currDealer <- Try(file.parseJson.convertTo[UsingDealer])
          } yield serviceControl ! Start(currDealer)
          else {
            Success(serviceControl ! Stop)
          }
        }

        onComplete((serviceControl ? GetStatus).mapTo[Status]) {
          case Success(serviceStat) => complete(getHtml(serviceStat.status.toString))
          case Failure(NonFatal(ex)) => complete(getHtml(s"Application malformed: `${ex.getMessage}`"))
        }
      }
    }
  }

  def getHtml(status: String) = {
    <html>
      <body>
        <h2>Content Analysis System</h2>
        <span>Status: { status }</span>
        <br/>
        <a href="/?isRun=true">Start</a> <a href="/?isRun=false">Stop</a>
      </body>
    </html>
  }
}