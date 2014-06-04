package slamdata.engine.api

import unfiltered.request._
import unfiltered.response._

import slamdata.engine._
import slamdata.engine.config._

import scalaz.concurrent._

object Server {
  def run(port: Int, fs: Map[String, Backend]): Task[Unit] = Task.delay {
    unfiltered.netty.Http(port).plan(new FileSystem(fs).api).run()
  }

  def main(args: Array[String]) {
    (for {
      config  <- args.headOption.map(Config.fromFile _).getOrElse(Task.now(Config.DefaultConfig))
      mounted <- Mounter.mount(config)
      _       <- run(config.server.port.getOrElse(8080), mounted)
    } yield ()).run
  }
}