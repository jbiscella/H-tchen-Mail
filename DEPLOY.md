# Deploying the Heikin Ashi Monitoring Service on a fresh AWS account

End-to-end procedure, Day 0 to first email. Some steps wait on AWS
support approval (hours to days) — those are flagged and kicked off
first so they're done by the time you need them.

| Cross-reference | Purpose |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) §12 | High-level pre-deploy checklist (12 rows) |
| [`terraform/bootstrap/README.md`](terraform/bootstrap/README.md) | Bootstrap stack details |
| [`terraform/main/README.md`](terraform/main/README.md) | Main stack architecture + code-vs-shape deploy model |
| [`.github/workflows/ci.yml`](.github/workflows/ci.yml) `env:` | Authoritative list of repo Variables the workflow consumes |

---

## 0. Tooling on your laptop

| Tool | Version | Check |
|---|---|---|
| AWS CLI v2 | ≥ 2.15 | `aws --version` |
| Terraform | ≥ 1.6 | `terraform version` |
| Git | any modern | |
| JDK 25 + Maven 3.9+ (optional) | for running `mvn verify` locally | `java --version` |

Configure the CLI for the **target account** using a profile with admin
or near-admin rights. The bootstrap apply is the only time you'll need
broad permissions; afterwards everything runs via the OIDC role.

```bash
aws configure --profile monitoring-bootstrap
# Region: eu-central-1 (or your override)
export AWS_PROFILE=monitoring-bootstrap
aws sts get-caller-identity   # sanity check
```

---

## 1. Kick off the slow async stuff (Day 0)

Start these first — they block the final deploy step but take hours
to days of waiting.

### 1.1 Request SES production access

AWS Console → SES → Account dashboard → **Request production access**.

- Region: **`eu-central-1`** (the SES region this project targets — see
  `terraform/main/variables.tf` `ses_region` default).
- Use case: transactional / alerting emails.
- Daily sending quota: 200/day is plenty.

While that's pending, you can verify the sender identity (next step) —
sandbox mode is fine for verifying identities, just not for sending to
addresses you don't own.

### 1.2 Verify the SES sender email

AWS Console → SES (**eu-central-1**) → Verified identities → **Create
identity** → "Email address" → enter your sender (e.g.
`alerts@yourdomain.com`).

- Click the confirmation link in the inbox.
- Identity status changes to **Verified**.

### 1.3 Request Bedrock model access

AWS Console → Bedrock (**eu-central-1**) → Model access → **Manage
model access** → request access for `Anthropic Claude Haiku 4.5` (the
default in `var.bedrock_model_id`).

- Approval for Claude models is usually instant.

---

## 2. Clone (or fork) the repo

```bash
git clone git@github.com:jbiscella/H-tchen-Mail.git
cd H-tchen-Mail
```

If you're deploying under a **different** GitHub repo, edit
`terraform/bootstrap/variables.tf` `github_repository` (default
`jbiscella/H-tchen-Mail`) before applying — the OIDC trust policy pins
to that repo.

---

## 3. Apply the bootstrap stack (once per account, manually)

Creates the prerequisites the main stack + CI workflow need: state
bucket, DynamoDB lock table, GitHub OIDC provider, deploy IAM role.

```bash
cd terraform/bootstrap
terraform init
terraform plan
terraform apply
```

Type `yes` when prompted. Takes ~30 seconds.

Capture outputs — you'll wire them into GitHub in the next step:

```bash
terraform output -json > bootstrap-outputs.json
```

| Output | Where it goes |
|---|---|
| `deploy_role_arn` | GitHub repo variable `DEPLOY_ROLE_ARN` |
| `state_bucket_name` | GitHub variable `TF_STATE_BUCKET` (default `monitoring-tfstate`) |
| `lock_table_name` | GitHub variable `TF_LOCK_TABLE` (default `monitoring-tflock`) |

See [`terraform/bootstrap/README.md`](terraform/bootstrap/README.md) for
the full output reference and trust-policy details.

---

## 4. Configure GitHub repo variables

GitHub repo → Settings → Secrets and variables → Actions → **Variables**
tab → **New repository variable**.

| Variable | Value |
|---|---|
| `DEPLOY_ROLE_ARN` | from bootstrap output |
| `AWS_REGION` | `eu-central-1` |
| `TF_STATE_BUCKET` | `monitoring-tfstate` |
| `TF_LOCK_TABLE` | `monitoring-tflock` |
| `ARTIFACTS_BUCKET` | `monitoring-artifacts-<unique-suffix>` — S3 names are **globally unique**; pick something like `monitoring-artifacts-<account-id>` or `monitoring-artifacts-<your-org>` |
| `LAMBDA_MAIN_NAME` | `monitoring-main` |
| `LAMBDA_RETRY_NAME` | `retry-poller` |

Anything left blank falls through to `.github/workflows/ci.yml`'s `env:`
defaults, but setting them explicitly keeps the workflow account-aware.

All AWS-touching jobs gate on `vars.DEPLOY_ROLE_ARN != ''` — if you fork
the repo and don't set the role, only `mvn verify` + `terraform
fmt + validate` run.

---

## 5. Populate the sender-email secret in SSM

The one SecureString Terraform does not own (a `plan` diff would leak
it):

```bash
aws ssm put-parameter \
  --name /monitoring/ses/sender-email \
  --type SecureString \
  --value alerts@yourdomain.com \
  --region eu-central-1
```

Use the same email you verified in §1.2.

> **Note**: the app currently doesn't read SSM at runtime — runtime config
> comes from `application.yml` + Lambda env vars set in
> `terraform/main/lambda.tf`. The SSM catalog is operator documentation
> + a hook for future `micronaut-aws-parameter-store` wiring. The
> sender-email SecureString is still required because Terraform reads
> it indirectly via the SES identity it provisions.

---

## 6. Customize defaults (optional)

Most defaults match CLAUDE.md §14 and need no override. Things you may
want to change:

| File | Variable | Default | Why change |
|---|---|---|---|
| `terraform/main/variables.tf` | `ses_sender_email` | `alerts@example.com` | Must match your verified address |
| `terraform/main/variables.tf` | `region` | `eu-central-1` | If your compute lives elsewhere |
| `terraform/main/variables.tf` | `ses_region` | `eu-central-1` | If your SES identity lives elsewhere |
| `terraform/main/variables.tf` | `bedrock_model_id` | Claude Haiku 4.5 | If you've been granted a different model |

You can override at apply time (`-var ses_sender_email=...`) instead of
editing files, but most projects fork the defaults.

---

## 7. Push to `main`

The first push to `main` triggers the full CI pipeline:

1. `mvn verify` — tests + coverage gate
2. `terraform-validate` — `fmt -check -recursive` + `terraform
   validate` on both stacks
3. `terraform-apply` — creates the main stack: DynamoDB, both Lambdas
   (with a 1 KB placeholder jar), EventBridge cron, SNS topics, alarms,
   SES identity
4. `deploy-lambda` — uploads the real shaded jar to
   `monitoring-artifacts/lambda/<sha>/<file>.jar` and points the `live`
   alias at the new version

Watch progress in GitHub → Actions tab.

If you have no other commits, force a deploy with an empty commit:

```bash
git commit --allow-empty -m "trigger initial deploy"
git push origin main
```

The code-vs-shape split is detailed in
[`terraform/main/README.md`](terraform/main/README.md) — Terraform owns
the *shape* (memory, timeout, IAM, schedule); the CI deploy job owns
the *code*; `lifecycle { ignore_changes = [...] }` keeps `terraform
apply` from clobbering whatever CI deployed.

---

## 8. Verify

Once the pipeline goes green:

```bash
# Lambda is reachable and replies
aws lambda invoke \
  --function-name monitoring-main:live \
  --payload '{}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/out.json

cat /tmp/out.json
# Expect: {"processed":0,...} since no instruments are registered yet
```

Register your first instrument (until the `mon` CLI lands, do it via a
direct AWS SDK call against the `monitoring` DynamoDB table, or via a
manual `aws lambda invoke` with the equivalent payload). The `MainInput`
shape is `{"instrument_ids": ["<uuid>"]}` for manual one-shot runs.

Wait for the 22:00 UTC cron — or invoke manually — and check:

- **CloudWatch Logs** `/aws/lambda/monitoring-main` — the JSON summary
  line `main_run_summary` reports `duration_ms`, `processed`, `sent`,
  etc.
- **CloudWatch alarms** — should all sit at `OK`.
- **Your inbox** — an `[HA Alert] …` email when a pattern fires.

---

## Common snags

| Symptom | Fix |
|---|---|
| Workflow fails at `terraform-apply` with `AccessDenied creating SES identity` | SES region in `variables.tf` doesn't match where you verified the identity. Default is `eu-central-1`. |
| `terraform-apply` fails with `BucketAlreadyExists` on the artifacts bucket | `ARTIFACTS_BUCKET` clashes with a globally-existing S3 bucket name. Pick something more unique (e.g. include your account id). |
| Lambda 5xx at first invoke | Sender email in SSM doesn't match the verified SES identity. Re-run §5. |
| Bedrock returns `AccessDeniedException` in the AI section of an alert email | §1.3 wasn't completed for the configured model. Request access. |
| `terraform apply` fails on backend init | Bootstrap stack wasn't applied, or `TF_STATE_BUCKET` / `TF_LOCK_TABLE` repo variables are wrong. |
| Workflow skips the AWS-touching jobs | `DEPLOY_ROLE_ARN` repo variable is empty. |
| Push to `main` rejected | Branch protection requires PR. Open a PR and merge. |

---

## Adding an instrument

The `mon` CLI from CLAUDE.md §10 isn't implemented yet, so registration
happens via a direct `aws dynamodb transact-write-items` call. The
service's `InstrumentRegistry.register(...)` writes three items in one
atomic transaction (CLAUDE.md §4, Block 1):

| Item | `pk` | `sk` | Purpose |
|---|---|---|---|
| `UNIQUE_LOCK` | `TICKER#<EXCHANGE>#<TICKER>` | `LOCK` | Prevents duplicate `(ticker, exchange)` registrations |
| `INSTRUMENT` meta | `INSTRUMENT#<uuid>` | `META` | Display name, currency, status, GSI1 keys |
| `CONFIG` default | `INSTRUMENT#<uuid>` | `CONFIG` | Storage policy, tracked timeframes, patterns, recipients |

### Supported exchanges

The exchange you pass **must** be in
`/monitoring/exchanges/supported` (configured per deploy; see
[`application.yml`](src/main/resources/application.yml)):

| Exchange code | Suffix | Example tickers |
|---|---|---|
| `NASDAQ` | (none) | `AAPL`, `MSFT` |
| `NYSE` | (none) | `IBM` |
| `MIL` | `.MI` | `ENI.MI` (Eni) |
| `XETRA` | `.DE` | `SAP.DE` |
| `LSE` | `.L` | `BP.L`, `GAW.L` (Games Workshop) |
| `TSX` | `.TO` | `RY.TO` |
| `PAR` | `.PA` | `AIR.PA` (Airbus) |
| `AMS` | `.AS` | `ASML.AS` (Euronext Amsterdam) |
| `SWX` | `.SW` | `CFR.SW` (Richemont), `NESN.SW` (Nestlé) |
| `BME` | `.MC` | `AMS.MC` (Amadeus IT Group) |

To add yet another exchange, edit a single place: `terraform/main/locals.tf` `exchange_suffix_map`. Both the Lambda env vars (lambda.tf) and the SSM catalog (ssm.tf) reference that local, so one edit propagates everywhere. Commit + push; CI redeploys the Lambdas with the new env vars.

### Recipe — register an instrument

Generate a UUID, fill in the four placeholders, paste into a terminal
(macOS / Linux):

```bash
INSTRUMENT_ID=$(uuidgen | tr 'A-Z' 'a-z')
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
TICKER=GAW              # uppercase, no whitespace
EXCHANGE=LSE            # one of the supported codes above
NAME="Games Workshop Group plc"
CURRENCY=GBP
RECIPIENT=you@example.com   # who gets the alert emails
TABLE=monitoring        # override only if you changed var.table_name

aws dynamodb transact-write-items --transact-items "$(cat <<EOF
[
  { "Put": {
    "TableName": "$TABLE",
    "Item": {
      "pk":            {"S": "TICKER#$EXCHANGE#$TICKER"},
      "sk":            {"S": "LOCK"},
      "entity":        {"S": "UNIQUE_LOCK"},
      "instrument_id": {"S": "$INSTRUMENT_ID"},
      "created_at":    {"S": "$NOW"}
    },
    "ConditionExpression": "attribute_not_exists(pk)"
  }},
  { "Put": {
    "TableName": "$TABLE",
    "Item": {
      "pk":         {"S": "INSTRUMENT#$INSTRUMENT_ID"},
      "sk":         {"S": "META"},
      "entity":     {"S": "INSTRUMENT"},
      "id":         {"S": "$INSTRUMENT_ID"},
      "ticker":     {"S": "$TICKER"},
      "exchange":   {"S": "$EXCHANGE"},
      "name":       {"S": "$NAME"},
      "currency":   {"S": "$CURRENCY"},
      "status":     {"S": "active"},
      "created_at": {"S": "$NOW"},
      "updated_at": {"S": "$NOW"},
      "gsi1Pk":     {"S": "STATUS#active"},
      "gsi1Sk":     {"S": "INSTRUMENT#$INSTRUMENT_ID"}
    },
    "ConditionExpression": "attribute_not_exists(pk)"
  }},
  { "Put": {
    "TableName": "$TABLE",
    "Item": {
      "pk":                  {"S": "INSTRUMENT#$INSTRUMENT_ID"},
      "sk":                  {"S": "CONFIG"},
      "entity":              {"S": "CONFIG"},
      "storage_policy":      {"S": "ROLLING_WINDOW"},
      "rolling_window_size": {"N": "200"},
      "tracked_timeframes":  {"SS": ["1d"]},
      "patterns": {"M": {
        "color_change":  {"M": {"enabled": {"BOOL": true},  "min_streak_length": {"N": "3"}}},
        "strong_candle": {"M": {"enabled": {"BOOL": false}, "wick_tolerance": {"N": "0.001"}, "min_body_ratio": {"N": "0.5"}}},
        "doji":          {"M": {"enabled": {"BOOL": false}, "max_body_ratio": {"N": "0.1"}}}
      }},
      "recipients":         {"SS": ["$RECIPIENT"]},
      "enable_chart":       {"BOOL": true},
      "enable_ai_analysis": {"BOOL": true},
      "created_at":         {"S": "$NOW"},
      "updated_at":         {"S": "$NOW"}
    },
    "ConditionExpression": "attribute_not_exists(pk)"
  }}
]
EOF
)"

echo "Registered $TICKER on $EXCHANGE as $INSTRUMENT_ID"
```

Notes:

- The `color_change` pattern is enabled by default with a 3-bar streak —
  edit the `patterns.M` block before running to toggle the others.
- `recipients` is the addresses that receive the alert email; if empty
  the dispatch silently skips.
- The transaction will fail with `ConditionalCheckFailedException` on
  the lock item if the same `(exchange, ticker)` is already registered
  — that's the duplicate-instrument guard working.

### Verify

```bash
# The instrument shows up under "active" via GSI1:
aws dynamodb query \
  --table-name monitoring \
  --index-name gsi_status \
  --key-condition-expression "gsi1Pk = :pk" \
  --expression-attribute-values '{":pk":{"S":"STATUS#active"}}' \
  --projection-expression "id, ticker, exchange, #s" \
  --expression-attribute-names '{"#s":"status"}'
```

The next 22:00 UTC cron will pick it up, or trigger it manually:

```bash
aws lambda invoke \
  --function-name monitoring-main:live \
  --payload "$(printf '{"instrument_ids":["%s"]}' "$INSTRUMENT_ID" | base64)" \
  --cli-binary-format raw-in-base64-out \
  /tmp/out.json

cat /tmp/out.json
```

## Recurring deploys

After the first apply, every push to `main` re-runs the same pipeline:

- `mvn verify` enforces tests + coverage gate.
- `terraform-apply` reconciles infra drift.
- `deploy-lambda` uploads a fresh shaded jar and bumps the `live` alias
  to the new published version.

Rolling back code is just `aws lambda update-alias --name live
--function-version <previous>` — Terraform's `ignore_changes` ensures it
won't undo your rollback on the next apply.
