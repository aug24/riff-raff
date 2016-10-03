package magenta

import java.util.UUID

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{ListObjectsV2Request, ListObjectsV2Result}
import magenta.artifact.{S3JsonArtifact, S3Path}
import magenta.fixtures.{StubDeploymentType, StubTask, _}
import magenta.graph.{DeploymentGraph, DeploymentTasks, StartNode, ValueNode}
import magenta.json._
import magenta.tasks.{S3Upload, Task}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.language.existentials

class ResolverTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient = mock[AmazonS3Client]
  when(artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(new ListObjectsV2Result())

  val simpleExample = """
  {
    "stack": "web",
    "packages":{
      "htmlapp":{ "type":"aws-s3", "data":{"bucket":"test", "cacheControl":[]} }
    },
    "recipes":{
      "all":{
        "default":true,
        "depends":["index-build-only","api-only"]
      },
      "htmlapp-only":{
        "actions":["htmlapp.uploadStaticFiles"]
      }
    }
  }
                      """

  "resolver" should "parse json into actions that can be executed" in {
    val parsed = JsonReader.parse(simpleExample, new S3JsonArtifact("artifact-bucket","tmp/123"))
    val deployRecipe = parsed.recipes("htmlapp-only")

    val host = Host("host1", stage = CODE.name, tags=Map("group" -> "")).app(App("apache"))
    val lookup = stubLookup(host :: Nil)

    val resources = DeploymentResources(reporter, lookup, artifactClient)
    val tasks = Resolver.resolve(project(deployRecipe), parameters(deployRecipe), resources)

    val taskList: List[Task] = DeploymentGraph.toTaskList(tasks)
    taskList.size should be (1)
    taskList should be (List(
      S3Upload("test", Seq((new S3Path("artifact-bucket","tmp/123/packages/htmlapp"), "CODE/htmlapp")), publicReadAcl = true)
    ))
  }

  val app2 = App("the_2nd_role")

  val host = Host("the_host", stage = CODE.name).app(app1)

  val host1 = Host("host1", stage = CODE.name).app(app1)
  val host2 = Host("host2", stage = CODE.name).app(app1)

  val lookupTwoHosts = stubLookup(List(host1, host2))
  val resources = DeploymentResources(reporter, lookupSingleHost, artifactClient)

  it should "generate the tasks from the actions supplied" in {
    val taskGraph = Resolver.resolve(project(baseRecipe), parameters(baseRecipe), resources)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task number one"),
      StubTask("init_action_one per app task number two")
    ))
  }

  it should "prepare dependsOn actions correctly" in {
    val basePackageType = stubPackageType(Seq("main_init_action"))

    val mainRecipe = Recipe("main",
      actions = basePackageType.mkAction("main_init_action")(stubPackage) :: Nil,
      dependsOn = List("one"))

    val resources = DeploymentResources(reporter, lookupSingleHost, artifactClient)
    val taskGraph = Resolver.resolve(project(mainRecipe, baseRecipe), parameters(mainRecipe), resources)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task number one"),
      StubTask("init_action_one per app task number two"),
      StubTask("main_init_action per app task number one"),
      StubTask("main_init_action per app task number two")
    ))

  }

  it should "only include dependencies once" in {
    val basePackageType = stubPackageType(Seq("main_init_action", "init_action_two"))

    val indirectDependencyRecipe = Recipe("two",
      actions = basePackageType.mkAction("init_action_two")(stubPackage) :: Nil,
      dependsOn = List("one"))
    val mainRecipe = Recipe("main",
      actions = basePackageType.mkAction("main_init_action")(stubPackage) :: Nil,
      dependsOn = List("two", "one"))

    val resources = DeploymentResources(reporter, lookupSingleHost, artifactClient)
    val taskGraph = Resolver.resolve(project(mainRecipe, indirectDependencyRecipe, baseRecipe), parameters(mainRecipe), resources)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("init_action_one per app task number one"),
      StubTask("init_action_one per app task number two"),
      StubTask("init_action_two per app task number one"),
      StubTask("init_action_two per app task number two"),
      StubTask("main_init_action per app task number one"),
      StubTask("main_init_action per app task number two")
    ))
  }

  it should "not throw an exception if no hosts found and only whole app recipes" in {
    val nonHostRecipe = Recipe("nonHostRecipe",
      actions =  basePackageType.mkAction("init_action_one")(stubPackage) :: Nil,
      dependsOn = Nil)

    val resources = DeploymentResources(reporter, stubLookup(List()), artifactClient)
    Resolver.resolve(project(nonHostRecipe), parameters(nonHostRecipe), resources)
  }

  it should "resolve tasks from multiple stacks" in {
    val pkgType = StubDeploymentType(
      actions = {
        case "deploy" => pkg => (resources, target) => List(StubTask("stacked", stack = Some(target.stack)))
      },
      List("deploy")
    )
    val recipe = Recipe("stacked",
      actions = List(pkgType.mkAction("deploy")(stubPackage)))

    val proj = project(recipe, NamedStack("foo"), NamedStack("bar"), NamedStack("monkey"), NamedStack("litre"))
    val resources = DeploymentResources(reporter, stubLookup(), artifactClient)
    val taskGraph = Resolver.resolve(proj, parameters(recipe), resources)
    DeploymentGraph.toTaskList(taskGraph) should be (List(
      StubTask("stacked", stack = Some(NamedStack("foo"))),
      StubTask("stacked", stack = Some(NamedStack("bar"))),
      StubTask("stacked", stack = Some(NamedStack("monkey"))),
      StubTask("stacked", stack = Some(NamedStack("litre")))
    ))
  }

  it should "resolve tasks from multiple stacks into a parallel task graph" in {
    val pkgType = StubDeploymentType(
      actions = {
        case "deploy" => pkg => (resources, target) => List(StubTask("stacked", stack = Some(target.stack)))
      },
      List("deploy")
    )
    val recipe = Recipe("stacked",
      actions = List(pkgType.mkAction("deploy")(stubPackage)))

    val proj = project(recipe, NamedStack("foo"), NamedStack("bar"), NamedStack("monkey"), NamedStack("litre"))
    val resources = DeploymentResources(reporter, stubLookup(), artifactClient)
    val taskGraph = Resolver.resolve(proj, parameters(recipe), resources)
    val successors = taskGraph.orderedSuccessors(StartNode)
    successors.size should be(4)

    successors should be(List(
      ValueNode(DeploymentTasks(List(StubTask("stacked", stack = Some(NamedStack("foo")))), "project -> foo")),
      ValueNode(DeploymentTasks(List(StubTask("stacked", stack = Some(NamedStack("bar")))), "project -> bar")),
      ValueNode(DeploymentTasks(List(StubTask("stacked", stack = Some(NamedStack("monkey")))), "project -> monkey")),
      ValueNode(DeploymentTasks(List(StubTask("stacked", stack = Some(NamedStack("litre")))), "project -> litre"))
    ))
  }

  def parameters(recipe: Recipe) =
    DeployParameters(Deployer("Tester"), Build("project", "build"), CODE, RecipeName(recipe.name))
}