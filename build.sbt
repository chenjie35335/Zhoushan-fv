// Scala version
scalaVersion := "2.12.13"

// Library dependencies
libraryDependencies ++= Seq(
  "edu.berkeley.cs" %% "chisel3" % "3.6.0",
  "edu.berkeley.cs" %% "chiseltest" % "0.6.0",
  "cn.ac.ios.tis" %% "riscvspeccore" % "1.1-SNAPSHOT", 
  "org.scalamacros" %% "paradise" % "2.1.1" cross CrossVersion.full
)

// Compiler options
scalacOptions ++= Seq("-Xsource:2.11")

// Add Compiler Plugins
addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin_2.12.13" % "3.6.0")

// Additional Repositories
resolvers += "Sonatype Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"

// Module settings for sub-projects
lazy val difftest = project.in(file("difftest"))
  .settings(
    scalaVersion := "2.12.13",
    libraryDependencies += "edu.berkeley.cs" %% "chisel3" % "3.6.0"
  )

lazy val zhoushan = project.in(file("."))
  .settings(
    scalaVersion := "2.12.13",
    libraryDependencies ++= Seq(
      "edu.berkeley.cs" %% "chisel3" % "3.6.0"
    )
  )
  .dependsOn(difftest)