package com.hypertino.services.authfacebook

import java.nio.charset.StandardCharsets

import com.hypertino.authfacebook.api.{ValidationResult, ValidationsPost}
import com.hypertino.binders.annotations.fieldName
import com.hypertino.binders.json.DefaultJsonBindersFactory
import com.hypertino.binders.value.{Null, Obj, Text, Value}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{BadRequest, Created, ErrorBody, InternalServerError, MessagingContext, Ok, ResponseBase, Unauthorized}
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
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

private[authfacebook] case class AuthFacebookServiceConfig(appId: String, appToken: String, extraFields: Seq[String] = Seq("email"))
private[authfacebook] case class TokenDebugResult(
                                                 appId: String,
                                                 application: String,
                                                 expiresAt: Option[Long],
                                                 isValid: Boolean,
                                                 issuedAt: Option[Long],
                                                 scopes: Seq[String],
                                                 userId: String
                                                 )

private[authfacebook] case class TokenDebugResultData(data: TokenDebugResult)
private[authfacebook] case class FacebookError(message: String, @fieldName("type") typ: String)

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
  val keyFields = Map("email" → "email")

  // todo: support scheme configuration + backward compatibility?
  private val handlers = hyperbus.subscribe(this, log)

  def onValidationsPost(implicit post: ValidationsPost): Task[ResponseBase] = {
    validateAuthorizationHeader(post.body.authorization).map { case (facebookUserId, fields) ⇒
      val identityKeys: Map[String, Value] = keyFields.flatMap { case (kf, kt) ⇒
        fields.toMap.get(kf).map { f ⇒
          kt → f
        }
      } ++ Map(fbuName → Text(facebookUserId))

      Created(ValidationResult(
        identityKeys = Obj(identityKeys),
        extra = fields
      ))
    }
  }

  implicit val jsonFactory = new DefaultJsonBindersFactory[CamelCaseToSnakeCaseConverter.type]()

  private def validateAuthorizationHeader(authorization: String)(implicit mcx: MessagingContext): Task[(String, Value)] = {
    val spaceIndex = authorization.indexOf(" ")
    if (spaceIndex < 0 || authorization.substring(0, spaceIndex).compareToIgnoreCase("facebook") != 0) {
      Task.raiseError(BadRequest(ErrorBody("format-error")))
    }
    else {
      val accessToken = authorization.substring(spaceIndex + 1).trim
      val fieldsTask = if (serviceConfig.extraFields.isEmpty) Task.now(Null) else getFields(accessToken)
      Task.mapBoth(validateAccessToken(accessToken), fieldsTask.materialize){
        case (Some(facebookUserId), Success(value)) ⇒
          (facebookUserId, value)

        case (Some(facebookUserId), Failure(ex)) ⇒
          log.warn(s"Can't get Facebook user details with token", ex)
          (facebookUserId, Null)

        case _ ⇒
          throw Unauthorized(ErrorBody("facebook-token-is-not-valid", Some(s"Provided Facebook token isn't valid")))
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
        val jsonString = httpResponse.getResponseBody(StandardCharsets.UTF_8)
        Try(jsonString.parseJson[TokenDebugResultData].data) match {
          case Success(t) if t.isValid && t.appId == serviceConfig.appId ⇒
            Some(t.userId)
          case _ ⇒
            log.debug(s"Facebook token is not valid: $jsonString")
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

  private def getFields(accessToken: String)(implicit mcx: MessagingContext): Task[Value] = {
    val get = asyncHttpClient.prepareGet(s"https://graph.facebook.com/v2.10/me")
    get.addQueryParam("access_token", accessToken)
    get.addQueryParam("fields", serviceConfig.extraFields.mkString(","))
    taskFromListenableFuture(get.execute()).map { httpResponse ⇒
      if (httpResponse.getStatusCode == 200) {
        import com.hypertino.binders.json.JsonBinders._
        val jsonString = httpResponse.getResponseBody(StandardCharsets.UTF_8)
        if (log.isDebugEnabled) {
          log.debug(s"Response to /me: $jsonString")
        }
        val value = jsonString.parseJson[Value]
        if (value.error != Null) {
          val error = value.error.to[FacebookError]
          throw BadRequest(ErrorBody(code=error.typ, description=Some(error.message)))
        }
        value
      }
      else {
        val error = InternalServerError(ErrorBody("facebook-failure", Some(s"/me request returned ${httpResponse.getStatusCode}")))
        val response = httpResponse.getResponseBody(StandardCharsets.UTF_8)
        if (log.isDebugEnabled) {
          log.debug(s"${error.body.description.get} #${error.body.errorId}: $response")
        }
        throw error
      }
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
