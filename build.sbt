crossScalaVersions := Seq("2.12.1", "2.11.8")

scalaVersion in Global := "2.11.8"

lazy val `auth-service` = project in file(".") enablePlugins Raml2Hyperbus settings (
    name := "auth-facebook-service",
    version := "0.1-SNAPSHOT",
    organization := "com.hypertino",  
    resolvers ++= Seq(
      Resolver.sonatypeRepo("public")
    ),
    libraryDependencies ++= Seq(
      "com.hypertino" %% "hyperbus" % "0.2-SNAPSHOT",
      "com.hypertino" %% "service-control" % "0.3-SNAPSHOT",
      "com.hypertino" %% "typesafe-config-binders" % "0.13-SNAPSHOT",
      "org.asynchttpclient" % "async-http-client" % "2.0.33",
      "com.hypertino" %% "hyperbus-t-inproc" % "0.2-SNAPSHOT" % "test",
      "com.hypertino" %% "service-config" % "0.2-SNAPSHOT" % "test",
      "ch.qos.logback" % "logback-classic" % "1.1.8" % "test",
      "org.scalamock" %% "scalamock-scalatest-support" % "3.5.0" % "test",
      compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
    ),
    ramlHyperbusSources := Seq(
      ramlSource(
        path = "api/auth-facebook-service-api/auth-facebook.raml",
        packageName = "com.hypertino.authfacebook.api",
        isResource = false
      ),
      ramlSource(
        path = "api/auth-facebook-service-api/expects/user.raml",
        packageName = "com.hypertino.authfacebook.apiref.user",
        isResource = false
      )
    )
)
