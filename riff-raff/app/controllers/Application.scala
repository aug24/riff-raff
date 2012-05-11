package controllers

import play.api._
import play.api.mvc._
import magenta.json.DeployInfoJsonReader

trait Logging {
  implicit val log = Logger(getClass)
}

case class MenuItem(title: String, target: Call, identityRequired: Boolean) {
  def isActive(request: AuthenticatedRequest[AnyContent]) = target.url == request.path
}

object Menu {
  lazy val menuItems = Seq(
    MenuItem("Home", routes.Application.index, false),
    MenuItem("Deployment Info", routes.Application.deployInfo(stage = ""), true)
  )

  lazy val loginMenuItem = MenuItem("Login", routes.Login.login, false)

  def items(request: AuthenticatedRequest[AnyContent]) = {
    val loggedIn = request.identity.isDefined
    menuItems.filter { item =>
      !item.identityRequired ||
        (item.identityRequired && loggedIn)
    }
  }
}

object Application extends Controller {

  def index = NonAuthAction { implicit request =>
    request.identity.isDefined
    Ok(views.html.index(request))
  }

  def deployInfo(stage: String) = AuthAction { request =>
    lazy val parsedDeployInfo = {
      import sys.process._
      DeployInfoJsonReader.parse("/opt/bin/deployinfo.json".!!)
    }

    val stageAppHosts = parsedDeployInfo filter { host =>
      host.stage == stage || stage == ""
    } groupBy { _.stage } mapValues { hostList =>
      hostList.groupBy {
        _.apps
      }
    }

    Ok(views.html.deployinfo(request, stageAppHosts))
  }

  def profile = AuthAction { request =>
    Ok(views.html.profile(request))
  }

}