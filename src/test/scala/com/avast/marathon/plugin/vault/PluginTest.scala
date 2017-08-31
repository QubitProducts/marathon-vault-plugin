package com.avast.marathon.plugin.vault

import java.util.concurrent.TimeUnit

import com.bettercloud.vault.{Vault, VaultConfig}
import mesosphere.marathon.client.MarathonClient
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

@RunWith(classOf[JUnitRunner])
class PluginTest extends FlatSpec with Matchers {

  private lazy val marathonUrl = s"http://${System.getProperty("marathon.host")}:${System.getProperty("marathon.tcp.8080")}"
  private lazy val mesosSlaveUrl = s"http://${System.getProperty("mesos-slave.host")}:${System.getProperty("mesos-slave.tcp.5051")}"
  private lazy val vaultUrl = s"http://${System.getProperty("vault.host")}:${System.getProperty("vault.tcp.8200")}"

  it should "read existing secret" in {
    val roleName = "myrole"
    val secretName = "secret/somesecret"
    val secretAttr = "value"
    val secretValue = "shhhh!"

    val vaultConfig = new VaultConfig().address(vaultUrl).token("testroottoken").build()
    val vault = new Vault(vaultConfig)
    vault.logical().write("sys/policy/secret", Map[String, AnyRef]("rules" -> """path "secret/*" {capabilities = ["read"]} """).asJava)
    vault.logical().write("sys/auth/approle", Map[String, AnyRef]("type" -> "approle").asJava)
    vault.logical().write(s"auth/approle/role/${roleName}", Map[String, AnyRef]("policies" -> "secret").asJava)
    vault.logical().write(secretName, Map[String, AnyRef](secretAttr -> secretValue).asJava)

    val roleId = vault.logical().read(s"auth/approle/role/${roleName}/role-id").getData.get("role_id")

    val  getSecret = ( secretId :String) =>  {
      System.out.println(s"VAULT: RoleID $roleId")
      System.out.println(s"VAULT: SecretID $secretId")
      val vaultConfig = new VaultConfig().address(vaultUrl).build()
      System.out.println(s"VAULT: Config $vaultConfig")
      val vault = new Vault(vaultConfig)
      System.out.println(s"VAULT: Config $vault")
      val response = vault.auth().loginByAppRole("approle", roleId, secretId)
      val token = response.getAuthClientToken()
      System.out.println(s"VAULT: Token $token")

      val vaultClientConfig = new VaultConfig().address(vaultUrl).token(token).build()
      val vaultClient = new Vault(vaultClientConfig)

      val secretResponse = vaultClient.logical().read(secretName)
      System.out.println(s"VAULT: Secret Response $secretResponse")
      secretResponse.getData.get(secretAttr)
    }

    check(roleName, deployWithAppRole) { secretId =>
      getSecret(secretId) shouldBe secretValue
    }
  }

  private def deployWithAppRole(roleName: String): String = {
    val envVarName = "VAULT_APPROLE_SECRET_ID"
    val appId = roleName
    val json = s"""{ "id": "$appId","cmd": "${EnvAppCmd.create(envVarName)}" }"""
    System.out.println(s"MARATHON: $json")
    val marathonResponse = new MarathonClient(marathonUrl).put(roleName, json)
    System.out.println(s"MARATHON: $marathonResponse")

    appId
  }

  private def check(roleName: String, deployApp: String => String)(verifier: String => Unit) = {
    val envVarName = "VAULT_APPROLE_SECRET_ID"
    val client = MarathonClient.getInstance(marathonUrl)
    val eventStream = new MarathonEventStream(marathonUrl)

    val appId = deployApp(roleName)
    val appCreatedFuture = eventStream.when(_.eventType.contains("deployment_success"))
    Await.result(appCreatedFuture, Duration.create(20, TimeUnit.SECONDS))

    val agentClient = MesosAgentClient(mesosSlaveUrl)
    val state = agentClient.fetchState()

    val secretId = agentClient.waitForStdOutContentsMatch(envVarName, state.frameworks(0).executors(0),
      o => EnvAppCmd.extractEnvValue(envVarName, o),
      java.time.Duration.ofSeconds(30))

    verifier(secretId)

    client.deleteApp(appId)

    val appRemovedFuture = eventStream.when(_.eventType.contains("deployment_success"))
    Await.result(appRemovedFuture, Duration.create(20, TimeUnit.SECONDS))

    eventStream.close()
  }
}
