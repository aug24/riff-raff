package deployment

import java.util.UUID

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import deployment.DeployCoordinator.{CheckStopFlag, StartDeploy, StopDeploy}
import deployment.TaskRunner._
import magenta.Host
import magenta.tasks._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

object DeployCoordinatorTest {
  lazy val testConfig = ConfigFactory.parseMap(
    Map("akka.test.single-expect-default" -> "500").asJava
  ).withFallback(ConfigFactory.load())
}

class DeployCoordinatorTest extends TestKit(ActorSystem("DeployCoordinatorTest", DeployCoordinatorTest.testConfig))
  with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  import Fixtures._

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "DeployCoordinator" should "respond to StartDeploy with PrepareDeploy to runner" in {
    val dc = createDeployCoordinatorWithUnderlying()
    val record = createRecord()
    dc.actor ! StartDeploy(record)

    dc.probe.expectMsgPF(){
      case PrepareDeploy(pdRecord, reporter) =>
        pdRecord should be(record)
        reporter.messageContext.deployId should be(record.uuid)
        reporter.messageContext.parameters should be(record.parameters)
    }

    dc.ul.deployStateMap.keys should contain(record.uuid)
  }

  it should "queue a StartDeploy message if the deploy is already running" in {
    val dc = createDeployCoordinatorWithUnderlying()
    val record = createRecord(projectName="test", stage="TEST")
    val recordTwo = createRecord(projectName="test", stage="TEST")

    dc.actor ! StartDeploy(record)
    dc.probe.expectMsgClass(classOf[PrepareDeploy])

    dc.actor ! StartDeploy(recordTwo)
    dc.probe.expectNoMsg()
    dc.ul.deferredDeployQueue.size should be(1)
    dc.ul.deferredDeployQueue.head should be(StartDeploy(recordTwo))
  }

  it should "queue a StartDeploy message if there are already too many running" in {
    val dc = createDeployCoordinatorWithUnderlying(2)
    val record = createRecord(projectName="test", stage="TEST")
    val recordTwo = createRecord(projectName="test2", stage="TEST")
    val recordThree = createRecord(projectName="test3", stage="TEST")

    dc.actor ! StartDeploy(record)
    dc.probe.expectMsgClass(classOf[PrepareDeploy])
    dc.ul.deferredDeployQueue.size should be(0)

    dc.actor ! StartDeploy(recordTwo)
    dc.probe.expectMsgClass(classOf[PrepareDeploy])
    dc.ul.deferredDeployQueue.size should be(0)

    dc.actor ! StartDeploy(recordThree)
    dc.probe.expectNoMsg()
    dc.ul.deferredDeployQueue.size should be(1)
    dc.ul.deferredDeployQueue.head should be(StartDeploy(recordThree))
  }

  it should "respond to a DeployReady message with the appropriate first task" in {
    val dc = createDeployCoordinator()
    val record = createRecord()

    dc.actor ! StartDeploy(record)
    val prepareDeploy = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    val context = createContext(List(S3Upload("test-bucket", Seq())), prepareDeploy)
    dc.probe.reply(DeployReady(record, context))
    dc.probe.expectMsgPF(){
      case RunTask(r, t, _, _) =>
        t.task should be(S3Upload("test-bucket", Seq()))
        r should be(record)
    }
  }

  it should "respond to a final TaskCompleted message with cleanup" in {
    val dc = createDeployCoordinatorWithUnderlying()
    val record = createRecord()

    dc.actor ! StartDeploy(record)
    val prepareDeploy = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    val context = createContext(List(S3Upload("test-bucket", Seq())), prepareDeploy)
    dc.probe.reply(DeployReady(record, context))
    val runTask = dc.probe.expectMsgClass(classOf[RunTask])
    dc.probe.reply(TaskCompleted(record, runTask.task))
    dc.probe.expectNoMsg()

    dc.ul.deployStateMap.keys shouldNot contain(record.uuid)
  }

  it should "dequeue StartDeploy messages when deploys complete" in {
    val dc = createDeployCoordinator()
    val record = createRecord(projectName="test", stage="TEST")
    val recordTwo = createRecord(projectName="test", stage="TEST")

    dc.actor ! StartDeploy(record)
    dc.actor ! StartDeploy(recordTwo)
    val prepareDeploy = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    val context = createContext(List(S3Upload("test-bucket", Seq())), prepareDeploy)
    dc.probe.reply(DeployReady(record, context))
    val runTask = dc.probe.expectMsgClass(classOf[RunTask])
    dc.probe.reply(TaskCompleted(record, runTask.task))

    val prepareDeployTwo = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    prepareDeployTwo.record should be(recordTwo)
  }

  it should "process a list of tasks" in {
    val dc = createDeployCoordinator()
    val record = createRecord()

    dc.actor ! StartDeploy(record)
    val prepareDeploy = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    val context = createContext(threeSimpleTasks, prepareDeploy)

    dc.probe.reply(DeployReady(record, context))
    val runS3Upload = dc.probe.expectMsgClass(classOf[RunTask])
    runS3Upload.task.task should be(S3Upload("test-bucket", Seq()))

    dc.probe.reply(TaskCompleted(runS3Upload.record, runS3Upload.task))
    val runSayHello = dc.probe.expectMsgClass(classOf[RunTask])
    runSayHello.task.task should be(SayHello(Host("testHost")))

    dc.probe.reply(TaskCompleted(runSayHello.record, runSayHello.task))
    val runGrace = dc.probe.expectMsgClass(classOf[RunTask])
    runGrace.task.task should be(HealthcheckGrace(1000))

    dc.probe.reply(TaskCompleted(runGrace.record, runGrace.task))
    dc.probe.expectNoMsg()
  }

  it should "process a task failure" in {
    val dc = createDeployCoordinatorWithUnderlying()
    val record = createRecord()

    dc.actor ! StartDeploy(record)
    val prepareDeploy = dc.probe.expectMsgClass(classOf[PrepareDeploy])
    val context = createContext(threeSimpleTasks, prepareDeploy)

    dc.probe.reply(DeployReady(record, context))
    val runS3Upload = dc.probe.expectMsgClass(classOf[RunTask])
    runS3Upload.task.task should be(S3Upload("test-bucket", Seq()))

    dc.probe.reply(TaskFailed(runS3Upload.record, runS3Upload.task, new RuntimeException("Something bad happened")))
    dc.probe.expectNoMsg()
    dc.ul.deployStateMap.keySet shouldNot contain(record.uuid)
  }

  it should "set the stop flag" in {
    val dc = createDeployCoordinatorWithUnderlying()
    dc.actor ! StopDeploy(UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622"), "testUser")
    dc.probe.expectNoMsg()
    dc.ul.stopFlagMap should contain(UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622") -> Some("testUser"))
  }

  it should "correctly report the stop flag status" in {
    import akka.pattern.ask
    implicit val timeout = Timeout(1 second)

    val dc = createDeployCoordinator()
    val stopFlagResult = dc.actor ? CheckStopFlag(UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622")) mapTo manifest[Boolean]
    Await.result(stopFlagResult, timeout.duration) should be(false)

    dc.actor ! StopDeploy(UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622"), "testUser")
    expectNoMsg()

    val stopFlagResult2 = dc.actor ? CheckStopFlag(UUID.fromString("967c5ca9-36cb-4e1c-b317-983792cdf622")) mapTo manifest[Boolean]
    Await.result(stopFlagResult2, timeout.duration) should be(true)
  }

  case class DC(probe: TestProbe, actor: ActorRef)

  def createDeployCoordinator(maxDeploys: Int = 5) = {
    val runnerProbe = TestProbe()
    val taskRunnerFactory = (_: ActorRefFactory) => runnerProbe.ref
    val ref = system.actorOf(Props(classOf[DeployCoordinator], taskRunnerFactory, maxDeploys))
    DC(runnerProbe, ref)
  }

  case class DCwithUnderlying(probe: TestProbe, actor: ActorRef, ul: DeployCoordinator)

  def createDeployCoordinatorWithUnderlying(maxDeploys: Int = 5) = {
    val runnerProbe = TestProbe()
    val taskRunnerFactory = (_: ActorRefFactory) => runnerProbe.ref
    val ref = TestActorRef(new DeployCoordinator(taskRunnerFactory, maxDeploys))
    DCwithUnderlying(runnerProbe, ref, ref.underlyingActor)
  }
}
