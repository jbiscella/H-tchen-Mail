# Terraform main stack

Owns every AWS resource the monitoring service needs at runtime:

| File          | Resources                                                                |
|---------------|--------------------------------------------------------------------------|
| `dynamodb.tf` | `monitoring` table + `gsi_status` + `gsi_retry_due`, TTL on `ttl`, PITR  |
| `lambda.tf`   | `monitoring-main` + `retry-poller` (SnapStart, arm64) + `live` aliases   |
| `events.tf`   | EventBridge daily 22:00 UTC rule + 15-min retry-poller rule + targets    |
| `iam.tf`      | Per-Lambda execution roles sharing one inline policy                     |
| `sns.tf`      | DLQ topic + ops-alerts topic                                             |
| `s3.tf`       | `monitoring-artifacts` bucket (Lambda jars; CI uploads here)             |
| `ssm.tf`      | `/monitoring/*` config catalog from CLAUDE.md §14                        |
| `ses.tf`      | SES email identity (separate region via `aws.ses` provider alias)        |
| `alarms.tf`   | `MainNotRunning`, `MainFailureRate`, `RetryBacklog`, `BedrockHighErrorRate`, `LambdaErrors-*`, `DLQDepth` |
| `outputs.tf`  | ARNs / names consumed by the deploy CI job                               |

Depends on the **bootstrap** stack (sibling `terraform/bootstrap/`) for:

- the S3 state bucket + DynamoDB lock table referenced via
  `terraform init -backend-config=...`
- the GitHub Actions OIDC role used by the workflow

## Deploy model — code vs. shape

Terraform owns the *shape* of each Lambda (runtime, memory, timeout, IAM,
schedule, alarms). The CI deploy job owns the *code*: it uploads the jar
to `monitoring-artifacts/` then runs

```
aws lambda update-function-code   --function-name <name> --s3-bucket ... --s3-key ...
aws lambda publish-version        --function-name <name>
aws lambda update-alias --name live --function-name <name> --function-version <new>
```

`lifecycle { ignore_changes = [filename, source_code_hash, s3_bucket, ...] }`
on the function resources prevents `terraform apply` from rolling the code
back to the placeholder jar. Same idea on `aws_lambda_alias.*_live` —
`function_version` is owned by CI, not Terraform.

The placeholder jar (`data.archive_file.stub_jar`) is a 1KB zip with just
a manifest, present so the very first apply succeeds before the CI deploy
job has ever run.

## Apply

The workflow does this for you, but the manual equivalent is:

```bash
cd terraform/main

# pull the bootstrap outputs (state bucket + lock table)
terraform init \
  -backend-config="bucket=monitoring-tfstate" \
  -backend-config="dynamodb_table=monitoring-tflock" \
  -backend-config="region=eu-central-1"

terraform plan
terraform apply
```

## Manual prerequisites (before first apply)

The bootstrap stack covers the IAM / state side. The data plane needs a few
things that Terraform cannot drive end-to-end:

| Step                                  | Where                                               |
|---------------------------------------|-----------------------------------------------------|
| Verify the SES sender email           | check the inbox for the AWS confirmation link      |
| Request SES production access         | AWS Support console (sandbox → prod), can take days |
| Request Bedrock model access          | Bedrock → Model access in `var.region`              |
| Populate `/monitoring/ses/sender-email` SecureString | `aws ssm put-parameter --type SecureString` |

Variables can be overridden via `-var` or `terraform.tfvars`. Defaults match
CLAUDE.md §14:

| Variable                       | Default                                           |
|--------------------------------|---------------------------------------------------|
| `region`                       | `eu-central-1`                                    |
| `ses_region`                   | `eu-south-1`                                      |
| `lambda_runtime`               | `java25`                                          |
| `lambda_memory_mb`             | `1024`                                            |
| `lambda_main_timeout_seconds`  | `900`                                             |
| `lambda_retry_timeout_seconds` | `300`                                             |
| `bedrock_model_id`             | `anthropic.claude-haiku-4-5-20251001-v1:0`        |
| `log_retention_days`           | `30`                                              |
| `ses_sender_email`             | `alerts@example.com` — override per environment   |

## Ops notes

- `MainNotRunning` deliberately uses a 26-hour evaluation period — the
  daily cron runs at 22:00 UTC, so 26h gives ~2h of grace before alerting.
- `lambda:InvokeFunction` permissions are pinned to the `live` alias
  qualifier; an unpublished `$LATEST` will not be schedulable.
- SSM parameter `value`s are `ignore_changes = [value]` so operators can
  hand-edit a knob (e.g. tighten the failure-rate threshold) without
  Terraform clobbering it on the next apply.
