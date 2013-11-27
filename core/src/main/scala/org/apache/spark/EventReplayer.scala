package org.apache.spark

import java.io.{EOFException, File, FileInputStream, PrintWriter}
import java.util.concurrent.ConcurrentHashMap

import org.apache.spark.rdd.{ForallAssertionRDD, RDD}
import org.apache.spark.scheduler._
import org.apache.spark.scheduler.SparkListenerTaskStart
import org.apache.spark.util.Utils.~>
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import com.google.common.io.Files

case class AssertionFailure(rddId: Int, partition: Int, element: Any)

class EventReplayer(context: SparkContext, var eventLogPath: String = null) {
  private[this] val stream = {
    if (eventLogPath == null) {
      eventLogPath = System.getProperty("spark.eventLogging.eventLogPath")

      require(
        eventLogPath != null,
        "Please specify the event log path, " +
        "either by setting the \"spark.eventLogging.eventLogPath\", " +
        "or by constructing EventReplayer with a legal event log path")
    }

    new EventLogInputStream(new FileInputStream(new File(eventLogPath)), context)
  }

  private[this] val _events = new ArrayBuffer[SparkListenerEvents]

  private[this] val rddIdToCanonical = new ConcurrentHashMap[Int, Int]

  val rdds = new ConcurrentHashMap[Int, RDD[_]]

  val tasks = new ConcurrentHashMap[(Int, Int), Task[_]]

  val taskEndReasons = new ConcurrentHashMap[Task[_], TaskEndReason]

  context.eventLogger.foreach(_.registerEventReplayer(this))
  loadEvents()

  def events = _events.readOnly

  def tasksForRDD(rdd: RDD[_]) =
    for {
      task <- tasks.values
      taskRDD <- task match {
        case t: ResultTask[_, _] => Some(t.rdd)
        case t: ShuffleMapTask => Some(t.rdd)
        case _ => None
      }
      if taskRDD.id == rdd.id
    } yield task

  def taskWithId(stageId: Int, partitionId: Int): Option[Task[_]] =
    Option(tasks.get((stageId, partitionId)))

  private[this] def loadEvents() {
    try {
      while (true) {
        val event = stream.readObject().asInstanceOf[SparkListenerEvents]
        appendEvent(event)
      }
    }
    catch {
      case _: EOFException => stream.close()
    }
  }

  private[this] def collectJobRDDs(job: ActiveJob) {
    def collectJobStages(stage: Stage, visited: Set[Stage]): Set[Stage] =
      if (!visited.contains(stage)) {
        val newVisited = visited + stage
        val ancestorStages = for {
          parent <- stage.parents.toSet[Stage]
          ancestor <- collectJobStages(parent, newVisited)
        } yield ancestor
        ancestorStages + stage
      }
      else {
        Set.empty[Stage]
      }

    def collectStageRDDs(rdd: RDD[_], visited: Set[RDD[_]]): Set[RDD[_]] =
      if (!visited.contains(rdd)) {
        val newVisited = visited + rdd
        val ancestorRDDs = for {
          dep <- rdd.dependencies.toSet[Dependency[_]]
          ancestor <- dep match {
            case _: ShuffleDependency[_, _] => Set.empty[RDD[_]]
            case _ => collectStageRDDs(dep.rdd, newVisited)
          }
        } yield ancestor
        ancestorRDDs + rdd
      }
      else {
        Set.empty[RDD[_]]
      }

    for {
      stage <- collectJobStages(job.finalStage, Set.empty[Stage])
      rdd <- collectStageRDDs(stage.rdd, Set.empty[RDD[_]])
    } {
      context.updateRddId(rdd.id)
      rdds(rdd.id) = rdd
      rddIdToCanonical(rdd.id) = rdd.id
    }
  }

  private[spark] def appendEvent(event: SparkListenerEvents) {
    _events += event

    event match {
      case SparkListenerTaskStart(task, _) =>
        tasks((task.stageId, task.partitionId)) = task

      case SparkListenerTaskEnd(task, reason, _, _) =>
        taskEndReasons(task) = reason

      case SparkListenerJobStart(job, _) =>
        // Records all RDDs within the job
        collectJobRDDs(job)

      case _ =>
    }
  }

  private[this] def rddType(rdd: RDD[_]) = rdd.getClass.getSimpleName

  /**
   * Visualizes the RDD DAG with GraphViz
   *
   * @param format Output file format, can be "pdf", "png", "svg", etc., default to "pdf".  Please
   *               refer to GraphViz documentation for all supported file formats.
   * @return The absolution file path of the output file
   */
  def visualizeRDDs(path: String = null, format: String = "pdf") = {
    val extension = format
    val basename =
      if (path == null)
        File.createTempFile("spark-rdds-", "").getAbsolutePath
      else
        Files.getNameWithoutExtension(path)

    val outFilePath = basename + "." + extension
    val dotFile = new File(basename + ".dot")
    val dotFilePath = dotFile.getAbsolutePath
    val dot = new PrintWriter(dotFile)

    dot.println("digraph {")
    dot.println("  node[shape=rectangle]")

    for (rdd <- rdds.values()) {
      dot.println("  %d [label=\"#%d: %s\\n%s\"]"
        .format(rdd.id, rdd.id, rdd.getClass.getSimpleName, rdd.origin))
      for (dep <- rdd.dependencies) {
        dot.println("  %d -> %d;".format(rdd.id, dep.rdd.id))
      }
    }

    dot.println("}")
    dot.close()

    Runtime.getRuntime.exec("dot -Grankdir=BT -T%s %s -o %s"
      .format(format, dotFilePath, outFilePath)).waitFor()

    outFilePath
  }

  def printRDDs() {
    for (rdd <- rdds.values()) {
      println("#%d: %s %s".format(rdd.id, rddType(rdd), rdd.origin))
    }
  }

  private[this] def replace[T: ClassManifest](rdd: RDD[T], newRDD: RDD[T]) {
    val canonicalId = rddIdToCanonical(rdd.id)
    rdds(canonicalId) = newRDD
    rddIdToCanonical(newRDD.id) = canonicalId

    for (descendantRddIndex <- (canonicalId + 1) until rdds.size()) {
      val updatedRDD = rdds(descendantRddIndex).dependenciesUpdated(new (RDD ~> RDD) {
        def apply[U](dependency: RDD[U]) = {
          rdds(rddIdToCanonical(dependency.id)).asInstanceOf[RDD[U]]
        }
      })

      rdds(descendantRddIndex) = updatedRDD
      rddIdToCanonical(updatedRDD.id) = descendantRddIndex
    }
  }

  def assertForall[T: ClassManifest](rdd: RDD[_])(assertion: T => Boolean): RDD[T] = {
    val typedRDD = rdd.asInstanceOf[RDD[T]]
    val assertionRDD = new ForallAssertionRDD(typedRDD, assertion)
    replace(typedRDD, assertionRDD)
    assertionRDD
  }
}