package controllers

import akka.ConfigurationException
import play.api.Play.current
import play.api._
import play.api.libs.json.{Json, JsValue}
import play.api.mvc._
import play.api.Logger

import scala.reflect.io.{Directory, Path}
import scala.sys.process._

object Application extends Controller {

  val dir = Directory(Play.configuration.getString("ansible.playbooks").get)
  if (!dir.isDirectory) throw new ConfigurationException(s"$dir is not a directory")
  val ansible = Play.configuration.getString("ansible.command").get
  val vaultFile = Play.configuration.getString("ansible.vault_password_file").get

  def index = Action {
    Ok(views.html.index(dir, ansible, vaultFile))
  }

  def play(inventoryName: String, playbookName: String): Action[JsValue] = Action(parse.json) { request =>

    val inventory = dir / inventoryName
    val playbook = dir / playbookName

    val result = checkPath(inventory, "inventory") orElse {
      checkPath(playbook, "playbook")
    } orElse {

      val stdout = new StringBuilder
      val stderr = new StringBuilder
      val cmd = Seq(ansible,
        "-v",
        "-i", inventory.toString(),
        "-e", request.body.toString(),
        "--vault-password-file", vaultFile,
        playbook.toString())
      val cmdString = cmd.reduce((s, i) => s"$s $i")
      Logger.debug(s"calling ${cmdString}")
      val code = cmd ! ProcessLogger(stdout append _, stderr append _)
      if (code == 0) {
        Logger.info(s"Playbook success\ncommand: $cmdString\n$stdout")
        val message = stdout.toString.replace("\"", "'")
        Some(Ok(Json.parse(s"""{"status":"success", "message": "$message"}""")))
      }
      else {
        val message = s"Playbook failed, $cmdString,  exit code $code\\n$stdout\\n$stderr"
        Logger.warn(message)
        val escaped = message.replace("\"", "'")
        Some(ServiceUnavailable((Json.parse(s"""{"status":"failed", "message": "$escaped"}"""))))
      }
    }
    result.get
  }

  private def checkPath(file: Path, hint: String): Option[Result] = {
    if (file.exists) {
      None
    } else {
      Some(NotFound(s"File not found: $hint file: $file"))
    }
  }

  def ping = Action {
    Ok.withHeaders(CACHE_CONTROL -> "no-cache")
  }

}
