crossScalaVersions := Seq("2.12.3", "2.11.11")

scalaVersion := crossScalaVersions.value.head

lazy val `auth-service` = project in file(".") enablePlugins Raml2Hyperbus settings (
    name := "auth-facebook-service",
    version := "0.3-SNAPSHOT",
    organization := "com.hypertino",  
    resolvers ++= Seq(
      Resolver.sonatypeRepo("public")
    ),
    libraryDependencies ++= Seq(
      "com.hypertino" %% "hyperbus" % "0.4-SNAPSHOT",
      "com.hypertino" %% "service-control" % "0.3.0",
      "com.hypertino" %% "typesafe-config-binders" % "0.2.0",
      "org.asynchttpclient" % "async-http-client" % "2.0.37",
      "com.hypertino" %% "hyperbus-t-inproc" % "0.4-SNAPSHOT" % "test",
      "com.hypertino" %% "service-config" % "0.2.0" % "test",
      "ch.qos.logback" % "logback-classic" % "1.2.3" % "test",
      "org.scalamock" %% "scalamock-scalatest-support" % "3.5.0" % "test",
      compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
    ),
    ramlHyperbusSources := Seq(
      ramlSource(
        path = "api/auth-facebook-service-api/auth-facebook.raml",
        packageName = "com.hypertino.authfacebook.api",
        isResource = false
      )
    )
)
