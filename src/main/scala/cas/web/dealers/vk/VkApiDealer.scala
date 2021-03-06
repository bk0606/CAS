package cas.web.dealers.vk

import java.net.URLEncoder

import akka.event.Logging
import akka.actor.ActorSystem
import cas.analysis.subject.Subject
import cas.analysis.subject.components._
import cas.service.ARouter.Estimation
import cas.service.ContentDealer
import cas.utils.{Utils, Web}
import cas.web.dealers.vk.VkApiProtocol._
import cas.utils.StdImplicits._
import cas.utils.UtilAliases._
import spray.client.pipelining._
import spray.httpx.SprayJsonSupport._
import org.joda.time.{DateTime, Period}

import scala.collection.mutable
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import spray.json._
import cas.utils.StdImplicits.RightBiasedEither

import scala.concurrent.duration._
import org.ccil.cowan.tagsoup.jaxp.SAXFactoryImpl

import scala.xml.{Node, XML}
import scala.xml.parsing.NoBindingFactoryAdapter
import Utils._
import cas.persistence.searching.SearchEngine

import scala.util.control.NonFatal
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}

import cas.math.Mathf.sec2Millis
import cas.web.pages.{ControlPage, MonitoringPage}

object VkApiDealer {
  import VkApiProtocol._

  val id = "VkApi.json"
  val apiUrl = "https://api.vk.com/method/"
  val apiVersion = "5.50"

  val actualityThreshold = 0.3
  val queriesPerSec = 1

  val clientId = "5369112"
  val clientSecret = "fFZQIwuIPR5bXxRW0c0I"
  val scope = "wall,groups,stats,friends,offline"

  def apply(rawConfigs: String, searcher: SearchEngine)(implicit system: ActorSystem) = for {
    cfg <- Try(rawConfigs.parseJson.convertTo[VkApiConfigs])
  } yield new VkApiDealer(cfg, searcher)
}

class VkApiDealer(cfg: VkApiConfigs, searcher: SearchEngine)(implicit val system: ActorSystem) extends ContentDealer {
  import VkApiProtocol._
  import VkApiDealer._
  import system.dispatcher

  var startPostInd = -cfg.postsPerQuery
  val defaultParams = "owner_id" -> cfg.ownerId.toString :: "v" -> apiVersion :: Nil

  override def estimatedQueryFrequency = 1.second / queriesPerSec

  override def pullSubjectsChunk: Future[Either[ErrorMsg, Subjects]] = {
    val pipeline = sendReceive ~> unmarshal[VkFallible[VkResponse[VkPostsComments]]]
    startPostInd = if (startPostInd + cfg.postsPerQuery >= cfg.siftCount) 0 else startPostInd + cfg.postsPerQuery
    val postsCount = Math.min(cfg.siftCount - startPostInd, cfg.postsPerQuery)
    // println("startPostInd: " + startPostInd + ", pCnt: " + postsCount)
    for {
      resp <- pipeline(Get(buildRequest("execute", "access_token" -> cfg.token :: "v" -> apiVersion ::
        "code" -> URLEncoder.encode(commentsQuery(startPostInd, postsCount), "UTF-8") :: Nil)))
    } yield for {
      postsComments <- resp.errorOrResp.transform(_.getMessage, _.items)
    } yield for {
      VkPostsComments(post, comments) <- postsComments
      articleBody = Try(Await.result(updateIndex(post), 20.seconds)).getOrElse("")
      comment <- comments
      // d = new DateTime(comment.date * sec2Millis)
      // n = DateTime.now
      // _ = println("Comment: `" + comment.text + "` CmtDate: [" + d.getHourOfDay + ":" + d.getMinuteOfHour + "], NowDate: [" + n.getHourOfDay + ":" + n.getMinuteOfHour + "]")
    } yield Subject(List(
      ID(comment.id.toString),
      Subject(List(ID(post.id.toString))),
      Likability(comment.likes.count.toDouble),
      CreationDate(new DateTime(comment.date * sec2Millis)),
      Description(comment.text),
      if (comment.attachments.isDefined) Attachments(comment.attachments.get.map(_.kind)) else Attachments(Nil)
      // Article(post.id.toString, post.getFullText, articleBody)
    ))
  }

  override def pushEstimations(estims: Estimations): Future[Either[ErrorMsg, Any]] = {
    val pipeline = sendReceive ~> unmarshal[VkFallible[VkSimpleResponse]]
    val scriptLines = ( for {
      estim <- estims
      if estim.actuality < actualityThreshold
    } yield for {
      id <- estim.subj.getComponent[ID]
      _ = Try(logEstim(estim, "Deleting comment"))
    } yield {
      buildDelLine(cfg.ownerId.toString, id.value)
    }).map(_.right.getOrElse("")).mkString
    if (scriptLines.nonEmpty) for {
      resp <- pipeline(Get(buildRequest("execute","access_token" -> cfg.token ::
        "v" -> apiVersion :: "code" -> URLEncoder.encode(scriptLines.mkString + " return 0;") :: Nil)))
    } yield for {
      vkResp <- resp.errorOrResp.left.map(_.getMessage)
    } yield vkResp
    else Future { Right(VkSimpleResponse(1)) }
  }

  override def initialize: Future[Any] = {
    val queryCount = cfg.siftCount / cfg.postsPerQuery
    def _init(chunkF: Future[Any], ind: Int = 1): Future[Any] = {
      if (ind < queryCount) {
        Thread.sleep(estimatedQueryFrequency.toMillis)
        _init(chunkF.flatMap(_ => pullSubjectsChunk), ind + 1)
      }
      else chunkF
    }
    _init(pullSubjectsChunk)
  }

  // TODO: Make unified logging
  def updateIndex(post: VkPost) = {
    // println(s"Post: `${post.getFullText}`")
    val logPushing: PartialFunction[Try[Either[ErrorMsg, Any]], Unit] = {
      case Success(Left(err)) => println(s"Cannot push entity to searcher (Left): `$err`")
      case Failure(ex) => println(s"Cannot push entity to searcher (Failure): `${ex.getMessage}`")
      case elze => ()
    }
    val pushPostF = searcher.pushEntity(post.id.toString, post.getFullText)
    for {
      postErrOrAny <- pushPostF
      _ = pushPostF onComplete logPushing
      tryArticleOpt = Try(extractArticleContent(post.getFullText))
      if tryArticleOpt.isSuccess && tryArticleOpt.get.isDefined
      article = tryArticleOpt.get.get
      pushArticleF = searcher.pushEntity(post.id.toString + "_article", article)
      articleErrOrAny <- pushArticleF
      _ = pushArticleF onComplete logPushing
    } yield article
  }

  def extractArticleContent(post: String) = {
    val urlRgx = """https?:\/\/(www\.)?[-a-zA-Z0-9@:%._\+~#=]{2,256}\.[a-z]{2,6}\b([-a-zA-Z0-9@:%_\+.~#?&//=]*)""".r
    val url = urlRgx.findFirstMatchIn(post).map(_.group(0))
    // println("url: " + url)
    if (url.isDefined) {
      def attributeValueEquals(value: String)(node: Node) = {
        node.attributes.exists(_.value.text == value)
      }
      val xml = loadXml(scala.io.Source.fromURL(url.get).mkString)
      val article = xml \\ "_" filter attributeValueEquals("content-note")
      // println("article.text: " + article.text)
      Some(article.text)
    }
    else None
  }

  def logEstim(estim: Estimation, action: String) = {
    val descr = "[" + new DateTime().getHourOfDay + ":" + new DateTime().getMinuteOfHour + "]" +
      " Likes: `" + estim.subj.getComponent[Likability].get.value + "`" +
      " Time elapsed: `" + (new org.joda.time.Duration(estim.subj.getComponent[CreationDate].get.value,
                            DateTime.now()).getMillis / 1000).toString + " sec`"+
      " Total actuality: `" + estim.actuality + "`" +
      " Text: `" + estim.subj.getComponent[Description].get.text + "`"
    val postId = estim.subj.getComponent[Subject].right.get.getComponent[ID].right.get

    MonitoringPage.addDeletedComment(descr);

    // ConfigurePage.commentsStorage.pushComment(estim.subj.getComponent[ID].right.get.value, postId.value, descr)

    println(descr)
  }

  def commentsQuery(startPostInd: Long, postsCount: Int) =
    s"""
      | var posts = API.wall.get({"offset": $startPostInd, "count": $postsCount, "owner_id": ${cfg.ownerId}});
      | var postComments = [];
      | var postInd = 0;
      | var fetchAmt = 100;
      | while (postInd < $postsCount) {
      |  var comments = API.wall.getComments({"owner_id": ${cfg.ownerId},
      |     "post_id": posts.items[postInd].id, "count": fetchAmt, "need_likes": 1});
      |  var fetchedComments = comments.items.length;
      |  while (fetchedComments < comments.count) {
      |   comments.items = comments.items + (API.wall.getComments({"owner_id": ${cfg.ownerId},
      |     "offset": fetchedComments, "post_id": posts.items[postInd].id, "count": fetchAmt, "need_likes": 1}).items);
      |   fetchedComments = fetchedComments + fetchAmt;
      |  }
      |   postComments.push({
      |     "post": posts.items[postInd],
      |     "comments": comments.items
      |   });
      |   postInd = postInd + 1;
      | }
      | return { "count": postComments.length, "items": postComments };
    """.stripMargin

  // TODO: Make like commentsQuery
  def buildDelLine(ownerId: String, id: String) =
    s""" API.wall.deleteComment({ "owner_id": $ownerId, "comment_id": $id }); """

  def buildRequest(method: String, params: List[(String, String)]) = Web.buildRequest(apiUrl)(method)(params)
}

