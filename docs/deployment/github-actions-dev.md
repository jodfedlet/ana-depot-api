# Deploying the API to Azure (dev) with GitHub Actions

Workflow: [`.github/workflows/deploy-dev.yml`](../../.github/workflows/deploy-dev.yml). Target: the existing Azure Container App `ana-depot-api-dev` (resource group `ana-company-dev-rg`), image from the existing registry `anadepotdevregistry`.
This is **dev only**; it changes nothing in the application, its database contract ([ADR 0016](../adr/0016-database-provider-independence.md)) or its Dockerfile.

```
push to main / manual run
   └─ job "test":   mvn verify (Java 25, Testcontainers: PostgreSQL + MinIO)          red = nothing below runs
   └─ job "deploy": docker build (existing Dockerfile) -> OIDC sign-in -> docker push <registry>/ana-depot-api:<commit SHA>
                    -> az containerapp update --image ... -> wait for the revision to be Healthy -> GET /actuator/health/readiness
```

* The image tag is the full commit SHA. `latest` is never pushed or deployed, so every revision names the exact commit it runs.
* Only the **image** of the Container App changes. Its environment variables, secrets, ingress, scale rules and probes stay as configured in Azure.
* Only `main` deploys (`if: github.ref == 'refs/heads/main'`, and the federated credential below accepts only that branch). A manual run from another branch runs the tests and stops.
* Azure access is OIDC: **no password, client secret or registry key** exists in the repository or in GitHub. The three GitHub secrets are identifiers, not credentials.
* Third-party actions are pinned to commit SHAs (`actions/checkout` v4.2.2, `actions/setup-java` v4.7.1, `azure/login` v2.3.0). Everything else uses the `az` and `docker` CLIs of the runner.
* One deployment at a time (`concurrency: deploy-dev`, no cancel): a push during a rollout waits.

## 1. Azure setup (one time, by someone allowed to assign roles)

Nothing is assumed to exist except the resource group, the registry and the Container App. Replace the `<...>` values; names below use shell variables.

```bash
RG=ana-company-dev-rg
APP=ana-depot-api-dev
ACR=anadepotdevregistry
REPO=jodfedlet/ana-depot-api
LOCATION=<the resource group's region>

ACR_ID=$(az acr show -n $ACR --query id -o tsv)
APP_ID=$(az containerapp show -n $APP -g $RG --query id -o tsv)
```

### 1.1 Identity for GitHub Actions: a user-assigned managed identity with a federated credential
(The only Azure resource this guide creates. It needs no Microsoft Entra directory permission, only Azure RBAC. A service principal / app registration works the same way; use the same subject and roles.)

```bash
az identity create -g $RG -n id-ana-depot-api-dev-github -l $LOCATION
GH_CLIENT_ID=$(az identity show -g $RG -n id-ana-depot-api-dev-github --query clientId -o tsv)
GH_PRINCIPAL_ID=$(az identity show -g $RG -n id-ana-depot-api-dev-github --query principalId -o tsv)

# Trust GitHub's OIDC tokens issued for pushes and manual runs on main of this repository, and nothing else.
az identity federated-credential create -g $RG --identity-name id-ana-depot-api-dev-github \
  --name github-main \
  --issuer https://token.actions.githubusercontent.com \
  --subject "repo:$REPO:ref:refs/heads/main" \
  --audiences api://AzureADTokenExchange
```

| Federated credential field | Value |
|---|---|
| Issuer | `https://token.actions.githubusercontent.com` |
| Subject | `repo:jodfedlet/ana-depot-api:ref:refs/heads/main` (if you later add a GitHub *environment* to the deploy job, the subject becomes `repo:jodfedlet/ana-depot-api:environment:<name>`) |
| Audience | `api://AzureADTokenExchange` |

### 1.2 RBAC for that identity (least privilege, two assignments, both on a single resource)

```bash
# push images (and nothing else) to the existing registry
az role assignment create --assignee-object-id $GH_PRINCIPAL_ID --assignee-principal-type ServicePrincipal --role AcrPush --scope $ACR_ID

# update, read revisions of this one Container App (no access to the rest of the resource group)
az role assignment create --assignee-object-id $GH_PRINCIPAL_ID --assignee-principal-type ServicePrincipal --role "Container Apps Contributor" --scope $APP_ID
```

Notes:
* If the registry uses **"RBAC Registry + ABAC Repository Permissions"** mode, `AcrPush` is not honoured: assign `Container Registry Repository Writer` (and `Container Registry Repository Reader` for the pull identity below) instead.
* If the Container App pulls with a **user-assigned** identity, the GitHub identity also needs `Managed Identity Operator` on that identity (the update references it). Using the app's **system-assigned** identity, as below, avoids this.
* The identity has no subscription-wide or resource-group role and cannot create, delete or read other resources.

### 1.3 The Container App pulls the image with its own managed identity (no registry password)

```bash
az containerapp identity assign -n $APP -g $RG --system-assigned
APP_PRINCIPAL_ID=$(az containerapp show -n $APP -g $RG --query identity.principalId -o tsv)

# order matters: the role must exist before the app is told to use the identity
az role assignment create --assignee-object-id $APP_PRINCIPAL_ID --assignee-principal-type ServicePrincipal --role AcrPull --scope $ACR_ID
az containerapp registry set -n $APP -g $RG --server $(az acr show -n $ACR --query loginServer -o tsv) --identity system
```

Check `az containerapp show -n $APP -g $RG --query properties.configuration.registries`: the entry for the registry must show `identity: system` and no `passwordSecretRef`.
Once that works, the registry's admin user (`az acr update -n $ACR --admin-enabled false`) can be switched off if nothing else uses it.
Role assignments can take a few minutes to propagate; if the first pull fails with an authorization error, wait and retry.

### 1.4 What the Container App itself must already satisfy (unchanged by this work)
* **Ingress target port `8080`** (the image's `EXPOSE`). Revision mode **Single** (the workflow then replaces the active revision once the new one is healthy; in Multiple mode you decide traffic yourself, see rollback).
* **Probes** (set in the portal under Containers, or YAML): liveness `GET /actuator/health/liveness`, readiness `GET /actuator/health/readiness` (port 8080, or the private `MANAGEMENT_SERVER_PORT`, see [operations.md](../operations.md) section 3). Readiness is DOWN when the database is unreachable; liveness never depends on it.
* **Configuration:** the environment variables and secrets in [docs/staging/api.staging.env.template](../staging/api.staging.env.template) (`SPRING_DATASOURCE_*`, Supabase and R2 values, ...) come from Container App secrets / Key Vault references, **never** from this repository or the workflow.
* **No Azure-managed source-control deployment** on this app (`az containerapp github-action show -n $APP -g $RG` must say there is none; do not use `az containerapp up`, `az containerapp github-action add`, or the portal's "Continuous deployment" for it). Those features generate their own workflow and registry credentials and would fight this one. Express-style / source-to-cloud shortcuts are likewise not used: the workflow builds the existing Dockerfile itself.

## 2. GitHub setup (repository `jodfedlet/ana-depot-api`)

**Secrets** (Settings > Secrets and variables > Actions > Repository secrets). They are identifiers; keep them as secrets so they are masked in logs:

| Secret | Value |
|---|---|
| `AZURE_CLIENT_ID` | `$GH_CLIENT_ID`: client ID of the managed identity of 1.1 |
| `AZURE_TENANT_ID` | the Microsoft Entra tenant ID (`az account show --query tenantId -o tsv`) |
| `AZURE_SUBSCRIPTION_ID` | the subscription that contains `ana-company-dev-rg` (`az account show --query id -o tsv`) |

**Variables** (same page, Repository variables). Optional: when unset the workflow uses the defaults shown, so a normal setup needs none:

| Variable | Default |
|---|---|
| `AZURE_RESOURCE_GROUP` | `ana-company-dev-rg` |
| `AZURE_CONTAINER_APP` | `ana-depot-api-dev` |
| `ACR_NAME` | `anadepotdevregistry` |
| `ACR_LOGIN_SERVER` | `anadepotdevregistry.azurecr.io` (set it if `az acr show -n anadepotdevregistry --query loginServer -o tsv` prints something else, e.g. a registry with a unique domain label) |

Recommended: protect `main` (pull request + passing checks), because **every push to `main` deploys to dev**. Do not store an Azure client secret, a registry password or a publish profile anywhere; none is used.

## 3. Triggering a deployment
* **Automatic:** push or merge to `main`.
* **Manual:** GitHub > Actions > "Deploy API (dev)" > *Run workflow* > branch `main`. Or `gh workflow run deploy-dev.yml --ref main`.
* **Re-deploy an older commit:** open that commit's earlier run and *Re-run all jobs* (it rebuilds and re-tests exactly that SHA), or use the rollback below to skip the build.

## 4. Inspecting the result
**GitHub:** Actions > the run. Job `test` (Maven output), job `deploy` (steps: build, sign-in, push, update, *Verify the new revision*). The run summary lists the commit, the image and the app. *Verify the new revision* prints the revision's running/health state every 10 s and then the readiness answer; it fails the run if the revision is `Failed`/`Degraded`, never becomes healthy in 5 minutes, or readiness is not `UP` in 3 minutes. A failed rollout leaves the previous revision serving (single-revision mode).

**Azure Portal:** Container Apps > `ana-depot-api-dev` > *Revisions and replicas* (the newest revision's image tag is the commit SHA; state, replicas, traffic %), *Log stream* and *Logs* (Log Analytics) for the application's JSON logs, *Containers* for the active image. CLI equivalents:

```bash
az containerapp revision list -n ana-depot-api-dev -g ana-company-dev-rg -o table
az containerapp show -n ana-depot-api-dev -g ana-company-dev-rg --query "properties.template.containers[0].image" -o tsv   # which commit runs now
az containerapp logs show -n ana-depot-api-dev -g ana-company-dev-rg --follow
```

## 5. Rolling back
Every image stays in the registry under its commit SHA, so a rollback is "run the previous SHA".

1. Find the previous good image: `az containerapp revision list ... -o table` (or the previous successful run's summary), e.g. `anadepotdevregistry.azurecr.io/ana-depot-api:<old-sha>`.
2. Single revision mode (recommended): deploy it as a new revision, without a rebuild:
   ```bash
   az containerapp update -n ana-depot-api-dev -g ana-company-dev-rg --image anadepotdevregistry.azurecr.io/ana-depot-api:<old-sha>
   ```
   Alternative: Portal > the app > *Revisions and replicas* > select the previous revision > *Activate*, then deactivate the bad one. In **Multiple** revision mode, move the traffic instead:
   `az containerapp ingress traffic set -n ana-depot-api-dev -g ana-company-dev-rg --revision-weight <good-revision>=100`.
3. Confirm with the readiness endpoint and the `az containerapp show ... --query ...image` command above.
4. Then fix forward on `main`. A push to `main` deploys again, so revert or fix the commit first if you do not want the bad build redeployed (or disable the workflow: Actions > the workflow > *...* > *Disable workflow*).

**Database caution:** Flyway migrations run at application start and are never rolled back. An older image runs against the schema the newer one already migrated; that is fine for additive migrations (the project's habit), but check the migrations between the two commits (`src/main/resources/db/migration`) before rolling back past one that drops or renames a column (`ddl-auto=validate` would refuse to start).

## 6. Verification status
Verified locally: the workflow file parses as YAML, every `run` block passes `bash -n`, the Dockerfile is the existing one, and the pinned action SHAs were read from GitHub's public API for the tags named above. **Not verified:** nothing was executed on GitHub or Azure (no run, no push, no deploy); the `az` flags, the role names against your registry mode and the federated-credential exchange are checked only when the first run happens. Do not treat the pipeline as proven until a first run is green and the revision is healthy.

Manual configuration still required: 1.1 to 1.3 (identity, federated credential, two role assignments, AcrPull and registry identity for the app), the three GitHub secrets, confirming the 1.4 settings of the Container App, and (recommended) protecting `main`.
