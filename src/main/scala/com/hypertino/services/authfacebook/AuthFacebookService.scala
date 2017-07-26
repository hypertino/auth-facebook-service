package com.hypertino.services.authfacebook

import java.nio.charset.StandardCharsets

import com.hypertino.authfacebook.api.{ValidationResult, ValidationsPost}
import com.hypertino.binders.json.DefaultJsonBindersFactory
import com.hypertino.binders.value.{Null, Obj}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{BadRequest, Created, ErrorBody, MessagingContext, Ok, ResponseBase, Unauthorized}
import com.hypertino.inflector.naming.CamelCaseToSnakeCaseConverter
import com.hypertino.service.control.api.Service
import com.typesafe.config.Config
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import org.asynchttpclient.{DefaultAsyncHttpClient, ListenableFuture}
import org.slf4j.LoggerFactory
import scaldi.{Injectable, Injector}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.NonFatal

private[authfacebook] case class AuthFacebookServiceConfig(appId: String, appToken: String)
private[authfacebook] case class TokenDebugResult(
                                                 appId: String,
                                                 application: String,
                                                 expiresAt: Long,
                                                 isValid: Boolean,
                                                 issuedAt: Option[Long],
                                                 scopes: Seq[String],
                                                 userId: String
                                                 )
//private[authfacebook] case class AccessTokenValidationResult(facebookUserId: String)

class AuthFacebookService(implicit val injector: Injector) extends Service with Injectable {
  private implicit val scheduler = inject[Scheduler]
  private val hyperbus = inject[Hyperbus]
  private val config = inject[Config]
  private val log = LoggerFactory.getLogger(getClass)
  log.info("AuthFacebookService started")

  import com.hypertino.binders.config.ConfigBinders._
  private val serviceConfig = config.read[AuthFacebookServiceConfig]("facebook")
  private val asyncHttpClient = new DefaultAsyncHttpClient

  // todo: configurable field name?
  val fbuName = "facebook_user_id"

  // todo: support scheme configuration + backward compatibility?
  private val handlers = hyperbus.subscribe(this, log)

  def onValidationsPost(implicit post: ValidationsPost): Task[ResponseBase] = {
    validateAuthorizationHeader(post.body.authorization).map { facebookUserId ⇒
      Created(ValidationResult(
        identityKeys = Obj.from(
          fbuName → facebookUserId
        ),
        extra = Null
      ))
    }
  }

  implicit val jsonFactory = new DefaultJsonBindersFactory[CamelCaseToSnakeCaseConverter.type]()

  private def validateAuthorizationHeader(authorization: String)(implicit mcx: MessagingContext): Task[String] = {
    val spaceIndex = authorization.indexOf(" ")
    if (spaceIndex < 0 || authorization.substring(0, spaceIndex).compareToIgnoreCase("facebook") != 0) {
      Task.raiseError(BadRequest(ErrorBody("format-error")))
    }
    else {
      val accessToken = authorization.substring(spaceIndex + 1).trim
      validateAccessToken(accessToken).flatMap {
        case Some(facebookUserId) ⇒
          Task.now(facebookUserId)
        case None ⇒
          Task.raiseError(Unauthorized(ErrorBody("facebook-token-is-not-valid", Some(s"Provided Facebook token isn't valid"))))
      }
    }
  }

  private def validateAccessToken(accessToken: String): Task[Option[String]] = {
    val get = asyncHttpClient.prepareGet(s"https://graph.facebook.com/debug_token")
    get.addQueryParam("input_token", accessToken)
    get.addQueryParam("access_token", serviceConfig.appToken)
    taskFromListenableFuture(get.execute()).map { httpResponse ⇒
      if (httpResponse.getStatusCode == 200) {
        import com.hypertino.binders.json.JsonBinders._
        val result = httpResponse.getResponseBody(StandardCharsets.UTF_8).parseJson[TokenDebugResult]
        if (result.isValid && result.appId == serviceConfig.appId) {
          Some(result.userId)
        }
        else {
          log.debug(s"Facebook token is not valid: $result")
          None
        }
      }
      else {
        None
      }
    } onErrorRecoverWith {
      case NonFatal(e) ⇒
        log.debug("Facebook token validation failed", e)
        Task.eval(None)
    }
  }

  private def taskFromListenableFuture[T](lf: ListenableFuture[T]): Task[T] = Task.create { (scheduler, callback) ⇒
    lf.addListener(new Runnable {
      override def run(): Unit = {
        val r = Try(lf.get())
        callback(r)
      }
    }, null)
    new Cancelable {
      override def cancel(): Unit = lf.cancel(true)
    }
  }

  override def stopService(controlBreak: Boolean, timeout: FiniteDuration): Future[Unit] = Future {
    asyncHttpClient.close()
    handlers.foreach(_.cancel())
    log.info("AuthFacebookService stopped")
  }
}
