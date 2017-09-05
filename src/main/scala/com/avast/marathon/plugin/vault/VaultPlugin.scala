package com.avast.marathon.plugin.vault

import com.bettercloud.vault.{Vault, VaultConfig}
import mesosphere.marathon.plugin.plugin.PluginConfiguration
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.plugin.{ApplicationSpec, EnvVarSecretRef, PodSpec, Secret}
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos.ExecutorInfo.Builder
import org.apache.mesos.Protos.{TaskGroupInfo, TaskInfo}
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, _}

import scala.util.{Failure, Success, Try}

import scala.reflect.runtime.universe.{runtimeMirror}
import scala.tools.reflect.ToolBox

case class Configuration(
  address: String,
  token: String,
  varName: Option[String],
  getAppID: Option[String]
)

class VaultPlugin extends RunSpecTaskProcessor with PluginConfiguration {
  private val logger = LoggerFactory.getLogger(classOf[VaultPlugin])
  logger.info("Vault plugin instantiated")

  private var vault: Vault = _
  private var varName: String = _
  private var getAppID: (ApplicationSpec) => String  = _

  override def initialize(marathonInfo: Map[String, Any], configurationJson: JsObject): Unit = {
    val conf = configurationJson.as[Configuration](Json.reads[Configuration])
    assert(conf != null, "VaultPlugin not initialized with configuration info.")
    assert(conf.address != null, "Vault address not specified.")
    assert(conf.token != null, "Vault token not specified.")

    vault = new Vault(new VaultConfig().address(conf.address).token(conf.token).build())
    varName = conf.varName.getOrElse("VAULT_APPROLE_SECRET_ID")

    getAppID = {
      val getAppIDStr = conf.getAppID.getOrElse(
        """appSpec:ApplicationSpec => s"${appSpec.id}".stripPrefix("/").replace('/', '.')""")

      val mirror = runtimeMirror(getClass.getClassLoader)
      val tb = ToolBox(mirror).mkToolBox()

      val funcStr =  s"""
          import mesosphere.marathon.plugin.ApplicationSpec
          locally {
            ${getAppIDStr}
          }
        """
      val tree = tb.parse(funcStr)
      tb.eval(tree).asInstanceOf[ApplicationSpec => String]
    }

    logger.info(s"VaultPlugin initialized with $conf")
  }

  def taskInfo(appSpec: ApplicationSpec, builder: TaskInfo.Builder): Unit = {
    val envBuilder = builder.getCommand.getEnvironment.toBuilder
    val roleName = getAppID(appSpec)
    getAppRoleSecretIDFromVault(roleName) match {
      case Success(secretIDValue) => envBuilder.addVariables(Variable.newBuilder().setName(varName).setValue(secretIDValue))
      case Failure(e) => logger.error(s"SecretID for ${roleName} cannot be read from Vault", e)
    }

    val commandBuilder = builder.getCommand.toBuilder
    commandBuilder.setEnvironment(envBuilder)
    builder.setCommand(commandBuilder)
  }

  private def getAppRoleSecretIDFromVault(approle: String): Try[String] = Try {
      var writeArgs: java.util.Map[String, Object] = new java.util.HashMap[String, Object]
      val path = s"auth/approle/role/${approle}/secret-id"
      logger.info(s"writing to path: $path")
      val sid = vault.logical().write(path,writeArgs)
      logger.info(s"got: $sid")
      Option(sid.getData.get("secret_id")) match {
        case Some(secretValue) => Success(secretValue)
        case None => Failure(new RuntimeException(s"Could not retrieve secret-id for $approle"))
      }
  }.flatten

  def taskGroup(podSpec: PodSpec, executor: Builder, taskGroup: TaskGroupInfo.Builder): Unit = {
  }
}
