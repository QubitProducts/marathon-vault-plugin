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

case class Configuration(address: String, token: String)

class VaultPlugin extends RunSpecTaskProcessor with PluginConfiguration {

  private val logger = LoggerFactory.getLogger(classOf[VaultPlugin])
  logger.info("Vault plugin instantiated")

  private var vault: Vault = _

  override def initialize(marathonInfo: Map[String, Any], configurationJson: JsObject): Unit = {
    val conf = configurationJson.as[Configuration](Json.reads[Configuration])
    assert(conf != null, "VaultPlugin not initialized with configuration info.")
    assert(conf.address != null, "Vault address not specified.")
    assert(conf.token != null, "Vault token not specified.")
    vault = new Vault(new VaultConfig().address(conf.address).token(conf.token).build())
    logger.info(s"VaultPlugin initialized with $conf")
  }

  /*
    {
      "env": {
        "abc": "def",
        "DB_PASSWORD": {
          "secret": "db_pwd"
        }
      },
      "secrets": {
        "db_pwd": {
          "source": "/path/to/vault/secret@password"
        }
      }
    }
  */

  def taskInfo(appSpec: ApplicationSpec, builder: TaskInfo.Builder): Unit = {
    val envBuilder = builder.getCommand.getEnvironment.toBuilder
    getAppRoleSecretIDFromVault(s"${appSpec.id}") match {
      case Success(secretIDValue) => envBuilder.addVariables(Variable.newBuilder().setName("VAULT_APPROLE_SECRET_ID").setValue(secretIDValue))
      case Failure(e) => logger.error(s"SecretID for ${appSpec.id} cannot be read from Vault", e)
    }

    val commandBuilder = builder.getCommand.toBuilder
    commandBuilder.setEnvironment(envBuilder)
    builder.setCommand(commandBuilder)
  }

  private def getSecretValueFromVault(secret: Secret): Try[String] = Try {
    val source = secret.source
    val indexOfAt = source.indexOf('@')
    val indexOfSplit = if (indexOfAt != -1) indexOfAt else source.lastIndexOf('/')
    if (indexOfSplit > 0) {
      val path = source.substring(0, indexOfSplit)
      val attribute = source.substring(indexOfSplit + 1)
      Option(vault.logical().read(path).getData.get(attribute)) match {
        case Some(secretValue) => Success(secretValue)
        case None => Failure(new RuntimeException(s"Secret $source obtained from Vault is empty"))
      }
    } else {
      Failure(new RuntimeException(s"Secret $source cannot be read because it cannot be parsed"))
    }
  }.flatten

  private def getAppRoleSecretIDFromVault(approle: String): Try[String] = Try {
      var writeArgs: java.util.Map[String, Object] = new java.util.HashMap[String, Object]
      val path = s"auth/approle/role${approle}/secret-id"
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
