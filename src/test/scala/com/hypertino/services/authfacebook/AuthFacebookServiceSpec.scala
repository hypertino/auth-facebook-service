package com.hypertino.services.authfacebook

import com.hypertino.authfacebook.api.{Validation, ValidationsPost}
import com.hypertino.binders.value.Obj
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{Created, ErrorBody, MessagingContext, Unauthorized}
import com.hypertino.service.config.ConfigLoader
import com.typesafe.config.Config
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import scaldi.Module

import scala.concurrent.duration._

class AuthFacebookServiceSpec extends FlatSpec with Module with BeforeAndAfterAll with ScalaFutures with Matchers {
  implicit val scheduler = monix.execution.Scheduler.Implicits.global
  implicit val mcx = MessagingContext.empty
  bind [Config] to ConfigLoader()
  bind [Scheduler] identifiedBy 'scheduler to scheduler
  bind [Hyperbus] identifiedBy 'hyperbus to injected[Hyperbus]

  val config = inject[Config]
  val hyperbus = inject[Hyperbus]
  val fbTestUserId = config.getString("facebook.test-user-id")
  val fbTestUserToken = config.getString("facebook.test-user-token")
  val fbTestUserToken2 = config.getString("facebook.test-user-token2")

  val service = new AuthFacebookService()

  override def afterAll() {
    service.stopService(false, 10.seconds).futureValue
    hyperbus.shutdown(10.seconds).runAsync.futureValue
  }

  override implicit val patienceConfig = new PatienceConfig(scaled(Span(3000, Millis)))

  "AuthFacebookService" should "validate if token is valid" in {
    val r = hyperbus
      .ask(ValidationsPost(Validation(s"Facebook $fbTestUserToken")))
      .runAsync
      .futureValue

    r shouldBe a[Created[_]]
    r.body.identityKeys shouldBe Obj.from(
      "facebook_user_id" → fbTestUserId
    )
  }

  "AuthFacebookService" should "not authorize if token isn't valid" in {
    val r = hyperbus
      .ask(ValidationsPost(Validation("Facebook ABCDE")))
      .runAsync
      .failed
      .futureValue

    r shouldBe a[Unauthorized[_]]
    val b = r.asInstanceOf[Unauthorized[ErrorBody]].body
    b.code shouldBe "facebook-token-is-not-valid"
  }
}
