Plugin for [Marathon](https://mesosphere.github.io/marathon/) which injects a vault approle secret-id

This is an adaption of [marathon-vault-pluin](https://github.com/avast/marathon-vault-plugin), that will use a marathon app-id as a vault approle role for login.

After a successful login, `VAULT_APPROLE_SECRET_ID` should be set to the value of the secret-id. The content of the container should then use a role-id to log into the the role, and retrieve secrets with the resulting
token.

Please consult the [Start Marathon with plugins](https://mesosphere.github.io/marathon/docs/plugin.html#start-marathon-with-plugins) section of the official docs for a general overview of how plugins are enabled.

The plugin configuration JSON file will need to reference the Vault plugin as follows:

```json
{
  "plugins": {
    "envVarExtender": {
      "plugin": "mesosphere.marathon.plugin.task.RunSpecTaskProcessor",
      "implementation": "com.avast.marathon.plugin.vault.VaultPlugin",
      "configuration": {
        "address": "http://address_to_your_vault_instance:port",
        "token": "access_token"
      }
    }
  }
}
```

You will also need to start Marathon with the secrets feature being enabled. See [Marathon command line flags](https://mesosphere.github.io/marathon/docs/command-line-flags) for more details. In short, it can be enabled by
* specifying `--enable_features secrets` in Marathon command line
* specifying environment variable `MARATHON_ENABLE_FEATURES=secrets` when starting Marathon
