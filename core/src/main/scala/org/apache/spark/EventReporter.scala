package org.apache.spark

import org.apache.spark.rdd.RDD
import org.apache.spark.scheduler.{ResultTask, ShuffleMapTask, Task, TaskResult}
import org.apache.spark.util.Utils
import scala.actors.Actor._
import scala.actors._
import scala.actors.remote._
import scala.util.MurmurHash

sealed trait EventReporterMessage
case class LogEvent(entry: EventLogEntry) extends EventReporterMessage
case class StopEventReporter() extends EventReporterMessage

class EventReporterActor(eventLogWriter: EventLogWriter) extends DaemonActor with Logging {
  def act() {
    val port = Settings.masterPort

    RemoteActor.alive(port)
    RemoteActor.register('EventReporterActor, self)

    logInfo("Registered actor on port " + port)

    loop {
      react {
        case LogEvent(entry) =>
          eventLogWriter.log(entry)

        case StopEventReporter =>
          eventLogWriter.stop()
          reply('OK)
      }
    }
  }
}

class EventReporter(isMaster: Boolean) extends Logging {
  val debuggerEnabled = Settings.enabled
  val checksumEnabled = Settings.checksum

  var eventLogWriter = if (isMaster && debuggerEnabled) Some(new EventLogWriter) else None
  var reporterActor = initReporterActor()

  private[this] def initReporterActor() = (debuggerEnabled, isMaster) match {
    case (true, true) =>
      for (writer <- eventLogWriter) yield {
        val actor = new EventReporterActor(writer)
        actor.start()
        actor
      }

    case (true, false) => {
      val (host, port) = Settings.masterAddress
      Some(RemoteActor.select(Node(host, port), 'EventReporterActor))
    }

    case _ => None
  }

  def reportAssertionFailure(failure: AssertionFailure) {
    for (actor <- reporterActor)
      actor !! LogEvent(failure)
  }

  def reportException(exception: Throwable, task: Task[_]) {
    for (actor <- reporterActor)
      actor !! LogEvent(ExceptionEvent(exception, task))
  }

  def reportLocalException(exception: Throwable, task: Task[_]) {
    for (writer <- eventLogWriter)
      writer.log(ExceptionEvent(exception, task))
  }

  def reportRDDCreation(rdd: RDD[_], location: Array[StackTraceElement]) {
    for (writer <- eventLogWriter)
      writer.log(RDDCreation(rdd, location))
  }

  def reportTaskSubmission(tasks: Seq[Task[_]]) {
    for (writer <- eventLogWriter)
      writer.log(TaskSubmission(tasks))
  }

  def reportTaskChecksum(task: Task[_], result: TaskResult[_], serializedResult: Array[Byte]) {
    if (checksumEnabled) {
      val checksum = new MurmurHash[Byte](42)

      task match {
        case r: ResultTask[_, _] =>
          for (byte <- serializedResult)
            checksum(byte)

          val serializedFunc = Utils.serialize(r.func)
          val funcChecksum = new MurmurHash[Byte](42)

          for (byte <- serializedFunc)
            funcChecksum(byte)

          for (actor <- reporterActor)
            actor !! LogEvent(ResultTaskChecksum(
                r.rdd.id, r.partition, funcChecksum.hash, checksum.hash))

        case s: ShuffleMapTask =>
          val serializedAccumUpdates = Utils.serialize(result.accumUpdates)

          for (byte <- serializedAccumUpdates)
            checksum(byte)

          for (actor <- reporterActor)
            actor !! LogEvent(ShuffleMapTaskChecksum(s.rdd.id, s.partition, checksum.hash))

        case _ =>
          logWarning("Unknown task type: " + task)
      }
    }
  }

  def reportShuffleChecksum(rdd: RDD[_], partition: Int, outputSplit: Int, checksum: Int) {
    for (actor <- reporterActor)
      actor !! LogEvent(ShuffleOutputChecksum(rdd.id, partition, outputSplit, checksum))
  }

  def stop() {
    for (actor <- reporterActor)
      actor !? StopEventReporter
    
    eventLogWriter = None
    reporterActor = None
  }
}
