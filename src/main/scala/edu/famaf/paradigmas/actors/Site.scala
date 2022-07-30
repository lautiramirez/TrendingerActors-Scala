package edu.famaf.paradigmas

import scala.xml.XML
import scala.io.Source
import scala.util.{Try,Success,Failure}

import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._

import akka.actor.typed.ActorSystem
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import scala.concurrent.duration._
import akka.util.Timeout

import dispatch._, Defaults._
import scala.concurrent.Future

object Site {
  def apply(): Behavior[SiteCommand] = Behaviors.setup((context) =>
    new Site(context))

  sealed trait SiteCommand
  final case class Httpget(id: String, name: String, url: String,
    replyTo: ActorRef[SiteResponse]) extends SiteCommand
  final case class FeedResponse(msg: Seq[String], replyTo: ActorRef[SiteResponse])
    extends SiteCommand
  final case class FeedFailed(msg: String) extends SiteCommand
  final case class FeedSend(id: String, name: String, url: String, http: String,
     replyTo: ActorRef[SiteResponse]) extends SiteCommand

  sealed trait SiteResponse
  final case class SiteMessage(text: Seq[String]) extends SiteResponse
}

class Site(context: ActorContext[Site.SiteCommand])
    extends AbstractBehavior[Site.SiteCommand](context) {
  context.log.info("Site Started")

  implicit val timeout: Timeout = 3.seconds
  import Site._

  private def getRequest(urlr: String): Future[String] =
    Http.default(url(urlr) OK as.String)

  override def onMessage(msg: SiteCommand): Behavior[SiteCommand] = {
    msg match {
      case FeedResponse(msg, replyTo) => {
        replyTo ! SiteMessage(msg)
        Behaviors.same
      }
      case FeedFailed(msg) => {
        context.log.error(msg)
        Behaviors.same
      }
      case FeedSend(id, name, url, http, replyTo) => {
        val feed = context.spawn(Feed(), s"New_Feed_${id}")
        context.ask(feed, Feed.ParseRequest(id, name, url, http, _)) {
          case Success(Feed.FeedMessage(text)) => FeedResponse(text, replyTo)
          case Failure(e) => FeedFailed(e.getMessage)
        }
        Behaviors.same
      }
      case Httpget(id, name, url, replyTo) => {
        context.pipeToSelf(getRequest(url)) {
          case Success(http) => FeedSend(id, name, url, http, replyTo)
          case Failure(e) => FeedFailed(e.getMessage)
        }
        Behaviors.same
      }
    }
  }
}
