package controllers.admin

import java.time.ZoneId
import java.time.format.DateTimeFormatter

import controllers.AssetsFinder
import play.api.i18n.Lang
import play.api.mvc._
import services.{OAuth2, OAuthConfig, UserService}

import scala.util.control.NonFatal
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.ws.WSClient
import models._

import scala.util.control.Exception._

case class FormattedSignatory(id: String, name: String, provider: String, providerId: String,
                              providerScreenName: String, signed: String, avatarUrl: String)

class AdminController(components: ControllerComponents, config: OAuthConfig, oauth2: OAuth2,
  userService: UserService, ws: WSClient, implicit private val assetsFinder: AssetsFinder)(implicit ec: ExecutionContext)
  extends AbstractController(components) {

  private implicit val lang = Lang("en")

  private lazy val settings = config.github.copy(
    // When logging in to the admin section, we need to verify that the user is a member of the Typesafe organisation,
    // so request access to that.
    scopes = Seq("read:org")
  )

  private val Expires = 7200000

  private def redirectUri(implicit req: RequestHeader) = routes.AdminController.authenticate().absoluteURL()

  def login = Action { implicit req =>
    val state = oauth2.generateState
    Redirect(oauth2.signInUrl(settings, redirectUri, state)).withSession("state" -> state)
  }

  def authenticate = Action.async { implicit req =>

    import scala.concurrent.Future.{successful => sync}

    req.queryString.get("code").flatMap(_.headOption) match {

      case Some(code) =>
        // It's an access token request.  Get the state from the session and from the query string.
        (for {
          sessionState <- req.session.get("state")
          queryStateValues <- req.queryString.get("state")
          queryState <- queryStateValues.headOption
        } yield {
          // Verify that the state matches.
          if (queryState == sessionState) {
            (for {
              // Get the access token from the OAuth service
              accessToken <- oauth2.requestAccessToken(settings, redirectUri, code)
              // And the user organisation
              userOrgs <- getUserOrgs(accessToken)
            } yield {
              // Check that the user is an admin
              if (userOrgs.contains("typesafehub") || userOrgs.contains("lightbend")) {
                Redirect(routes.AdminController.index()).withSession("admin" -> "true",
                  "timestamp" -> System.currentTimeMillis().toString)
              } else {
                Forbidden("Not a member of Lightbend")
              }
            }).recover {
              case NonFatal(t) =>
                Logger.warn("Error logging in user", t)
                Forbidden
            }
          } else {
            // The state doesn't match, reject the request.
            sync(Forbidden("State doesn't match"))
          }
        }).getOrElse(sync(NotFound("State not found")))

      case None => sync(BadRequest)
    }
  }

  private def isAuthenticated(req: RequestHeader) = {
    (for {
      admin <- req.session.get("admin")
      if admin == "true"
      timestampStr <- req.session.get("timestamp")
      timestamp <- catching(classOf[NumberFormatException]).opt(timestampStr.toLong)
      if timestamp + Expires > System.currentTimeMillis()
    } yield true).getOrElse(false)
  }

  private object Authenticated extends ActionBuilder[Request, AnyContent] {
    def invokeBlock[AnyContent](request: Request[AnyContent], block: Request[AnyContent] => Future[Result]) = {
      if (isAuthenticated(request)) {
        block(request)
      } else {
        Future.successful(Redirect(routes.AdminController.index()))
      }
    }

    override def parser: BodyParser[AnyContent] = components.parsers.default
    override protected def executionContext: ExecutionContext = components.executionContext
  }

  def logout = Action {
    Redirect(routes.AdminController.index()).withNewSession
  }

  def index = Action { req =>
    if (isAuthenticated(req)) {
      Ok(views.html.admin.index.apply)
    } else {
      Ok(views.html.admin.login.apply)
    }
  }

  private def getFormatedSigs: Future[List[FormattedSignatory]] = {
    userService.loadSignatories().map(_.map { sig =>
      val (provider, id, screenName) = sig.provider match {
        case Twitter(id, screenName) => ("twitter", id.toString, screenName)
        case GitHub(id, screenName) => ("github", id.toString, screenName)
        case Google(id) => ("google", id, "")
        case LinkedIn(id) => ("linkedin", id, "")
      }
      val signed = sig.signed.map(format.format).getOrElse("")
      val avatarUrl = sig.avatarUrl.getOrElse("")
      FormattedSignatory(sig.id.stringify, sig.name, provider, id, screenName, signed, avatarUrl)
    })
  }

  def list = Authenticated.async { req =>
    getFormatedSigs.map { sigs =>
      Ok(views.html.admin.list(sigs))
    }
  }

  def csv = Authenticated.async { req =>
    getFormatedSigs.map { sigs =>
      Ok(sigs.map { sig =>
        s"""${sig.id},"${sig.name}",${sig.provider},${sig.providerId},"${sig.providerScreenName}",${sig.signed},"${sig.avatarUrl}""""
      }.mkString("\n")).as("text/csv").withHeaders("Content-Disposition" -> "attachment;filename=signatories.csv")
    }
  }

  private def getUserOrgs(accessToken: String): Future[Seq[String]] = {
    ws.url("https://api.github.com/user/orgs")
      .addQueryStringParameters("access_token" -> accessToken).get().map { response =>
      if (response.status == 200) {
        (response.json \\ "login").map(_.as[String])
      } else {
        throw new IllegalArgumentException("Error looking up profile information, status was: " + response.status + " " + response.body)
      }
    }
  }

  private val format = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.of("UTC"))

}
