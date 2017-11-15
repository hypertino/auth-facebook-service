package com.hypertino.services.authfacebook

import java.nio.charset.StandardCharsets

import com.hypertino.authfacebook.api.{ValidationResult, ValidationsPost}
import com.hypertino.binders.annotations.fieldName
import com.hypertino.binders.json.DefaultJsonBindersFactory
import com.hypertino.binders.value.{Null, Obj, Text, Value}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{BadRequest, Created, ErrorBody, InternalServerError, MessagingContext, ResponseBase, Unauthorized}
import com.hypertino.hyperbus.subscribe.Subscribable
import com.hypertino.inflector.naming.CamelCaseToSnakeCaseConverter
import com.hypertino.service.control.api.Service
import com.hypertino.services.authfacebook.utils.ErrorCode
import com.typesafe.config.Config
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import org.asynchttpclient.{DefaultAsyncHttpClient, ListenableFuture}
import com.typesafe.scalalogging.StrictLogging
import scaldi.{Injectable, Injector}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

private[authfacebook] case class AuthFacebookServiceConfig(baseUrl: String = "https://graph.facebook.com", appId: String, appToken: String, extraFields: Seq[String] = Seq("email"))
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

class AuthFacebookService(implicit val injector: Injector) extends Service with Injectable with Subscribable with StrictLogging {
  private implicit val scheduler = inject[Scheduler]
  private val hyperbus = inject[Hyperbus]
  private val config = inject[Config]

  import com.hypertino.binders.config.ConfigBinders._
  private val serviceConfig = config.read[AuthFacebookServiceConfig]("facebook")
  private val asyncHttpClient = new DefaultAsyncHttpClient

  // todo: configurable field name?
  private val fbuName = "facebook_user_id"
  private val keyFields = Map("email" → "email")

  // todo: support scheme configuration + backward compatibility?
  private val handlers = hyperbus.subscribe(this, logger)

  // todo: configurable retry count
  private val RETRY_COUNT = 3

  logger.info(s"${getClass.getName} is STARTED")

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
      Task.raiseError(BadRequest(ErrorBody(ErrorCode.FORMAT_ERROR)))
    }
    else {
      val accessToken = authorization.substring(spaceIndex + 1).trim
      val fieldsTask = if (serviceConfig.extraFields.isEmpty) Task.now(Null) else getFields(accessToken)
      Task.mapBoth(validateAccessToken(accessToken), fieldsTask.materialize){
        case (Some(facebookUserId), Success(value)) ⇒
          (facebookUserId, value)

        case (Some(facebookUserId), Failure(ex)) ⇒
          logger.warn(s"Can't get Facebook user details with token", ex)
          (facebookUserId, Null)

        case _ ⇒
          throw Unauthorized(ErrorBody(ErrorCode.FACEBOOK_TOKEN_IS_NOT_VALID, Some(s"Provided Facebook token isn't valid")))
      }
    }
  }

  private def validateAccessToken(accessToken: String)(implicit mcx: MessagingContext): Task[Option[String]] = {
    Task.eval {
      val get = asyncHttpClient.prepareGet(s"${serviceConfig.baseUrl}/debug_token")
      get.addQueryParam("input_token", accessToken)
      get.addQueryParam("access_token", serviceConfig.appToken)
      taskFromListenableFuture(get.execute())
    }
      .flatten
      .onErrorRestart(RETRY_COUNT)
      .map { httpResponse ⇒
      if (httpResponse.getStatusCode == 200) {
        import com.hypertino.binders.json.JsonBinders._
        val jsonString = httpResponse.getResponseBody(StandardCharsets.UTF_8)
        Try(jsonString.parseJson[TokenDebugResultData].data) match {
          case Success(t) if t.isValid && t.appId == serviceConfig.appId ⇒
            Some(t.userId)
          case _ ⇒
            logger.debug(s"Facebook token is not valid: $jsonString")
            None
        }
      }
      else {
        None
      }
    } onErrorRecoverWith {
      case NonFatal(e) ⇒
        logger.debug("Facebook token validation failed", e)
        Task.raiseError(InternalServerError(ErrorBody(ErrorCode.FACEBOOK_TOKEN_VALIDATION_FAILED, Some(e.toString))))
    }
  }

  private def getFields(accessToken: String)(implicit mcx: MessagingContext): Task[Value] = {
    Task.eval {
      val get = asyncHttpClient.prepareGet(s"${serviceConfig.baseUrl}/v2.10/me")
      get.addQueryParam("access_token", accessToken)
      get.addQueryParam("fields", serviceConfig.extraFields.mkString(","))
      taskFromListenableFuture(get.execute())
    }
      .flatten
      .onErrorRestart(RETRY_COUNT)
      .map { httpResponse ⇒
      if (httpResponse.getStatusCode == 200) {
        import com.hypertino.binders.json.JsonBinders._
        val jsonString = httpResponse.getResponseBody(StandardCharsets.UTF_8)
        logger.debug(s"Response to /me: $jsonString")
        val value = jsonString.parseJson[Value]
        if (value.dynamic.error != Null) {
          val error = value.dynamic.error.to[FacebookError]
          throw BadRequest(ErrorBody(code=error.typ, description=Some(error.message)))
        }
        value
      }
      else {
        val error = InternalServerError(ErrorBody(ErrorCode.FACEBOOK_FAILURE, Some(s"/me request returned ${httpResponse.getStatusCode}")))
        val response = httpResponse.getResponseBody(StandardCharsets.UTF_8)
        logger.debug(s"${error.body.description.get} #${error.body.errorId}: $response")
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
    logger.info(s"${getClass.getName} is STOPPED")
  }
}
