package com.hypertino.services.authfacebook

import com.hypertino.authfacebook.api.{Validation, ValidationsPost}
import com.hypertino.binders.json.DefaultJsonBindersFactory
import com.hypertino.binders.value.{Obj, Text}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{Created, ErrorBody, MessagingContext, Unauthorized}
import com.hypertino.hyperbus.subscribe.Subscribable
import com.hypertino.hyperbus.transport.api.ServiceRegistrator
import com.hypertino.hyperbus.transport.registrators.DummyRegistrator
import com.hypertino.inflector.naming.CamelCaseToSnakeCaseConverter
import com.hypertino.service.config.ConfigLoader
import com.typesafe.config.Config
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import scaldi.Module

import scala.concurrent.duration._

class AuthFacebookServiceSpec extends FlatSpec with Module with BeforeAndAfterAll with ScalaFutures with Matchers with Subscribable {
  implicit val scheduler = monix.execution.Scheduler.Implicits.global
  implicit val mcx = MessagingContext.empty
  bind [Config] to ConfigLoader()
  bind [Scheduler] to scheduler
  bind [Hyperbus] to injected[Hyperbus]
  bind [ServiceRegistrator] to DummyRegistrator

  val config = inject[Config]
  val hyperbus = inject[Hyperbus]
  val fbTestUserId = config.getString("facebook.test-user-id")
  val fbTestUserToken = config.getString("facebook.test-user-token")
//  val fbTestUserToken2 = config.getString("facebook.test-user-token2")

  val service = new AuthFacebookService()

  override def afterAll() {
    service.stopService(false, 10.seconds).futureValue
    hyperbus.shutdown(10.seconds).runAsync.futureValue
  }

  override implicit val patienceConfig = new PatienceConfig(scaled(Span(3000, Millis)))

  ignore should "validate if token is valid" in {
    val r = hyperbus
      .ask(ValidationsPost(Validation(s"Facebook $fbTestUserToken")))
      .runAsync
      .futureValue

    r shouldBe a[Created[_]]
    r.body.identityKeys.dynamic.facebook_user_id shouldBe Text(fbTestUserId)
    r.body.identityKeys.dynamic.email shouldBe a[Text]
    // println(r)
  }

  it should "not authorize if token isn't valid" in {
    val r = hyperbus
      .ask(ValidationsPost(Validation("Facebook ABCDE")))
      .runAsync
      .failed
      .futureValue

    r shouldBe a[Unauthorized[_]]
    val b = r.asInstanceOf[Unauthorized[ErrorBody]].body
    b.code shouldBe "facebook-token-is-not-valid"
  }

  "TokenDebugResultData" should "deserialize" in {
    val s =
      """
        {"data":{"app_id":"1559926294081781","application":"elbi-v2-dev","expires_at":1506630323,"is_valid":true,"issued_at":1501446323,"metadata":{"auth_type":"rerequest","sso":"iphone-safari"},"scopes":["email","public_profile"],"user_id":"101396227229647"}}
      """

    import com.hypertino.binders.json.JsonBinders._
    implicit val jsonFactory = new DefaultJsonBindersFactory[CamelCaseToSnakeCaseConverter.type]()
    s.parseJson[TokenDebugResultData].data
  }
}
