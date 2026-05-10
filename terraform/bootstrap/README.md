# Terraform bootstrap stack

One-shot setup that creates the prerequisites for the main Terraform
stack and for CI/CD deployments. Apply this **manually**, **once per
account**. After it's applied the values from `terraform output` get
wired into:

- the main stack's S3 backend (state bucket + lock table)
- the GitHub Actions workflow's `role-to-assume` (deploy role ARN)
- the repo's GitHub Actions secrets (AWS account ID, region)

This stack uses **local state** on purpose — putting it in S3 would
create a circular dependency on the very bucket it provisions.

## What it creates

| Resource | Purpose |
|---|---|
| `aws_s3_bucket.tfstate` | Holds the main-stack Terraform state. Versioning + AES256 SSE + public-access block + 90-day non-current expiry. |
| `aws_dynamodb_table.tflock` | Terraform state lock. PAY_PER_REQUEST, single string `LockID` key. |
| `aws_iam_openid_connect_provider.github` | GitHub Actions OIDC provider — lets the workflow assume an IAM role without static keys. |
| `aws_iam_role.deploy` | Role assumed by GitHub Actions for plan / apply / deploy. Trust pinned to `${var.github_repository}` on `${var.deploy_branch}` and pull-request runs. |
| `aws_iam_role_policy.deploy` | Inline policy giving the deploy role what it needs: TF state R/W, artifacts upload, full CRUD on the project's DynamoDB / Lambda / EventBridge / SNS / SSM / Logs / IAM-on-self / CloudWatch alarms. |

## Apply

```bash
cd terraform/bootstrap
terraform init
terraform plan
terraform apply
```

Variables can be overridden via `-var` or a `terraform.tfvars`. Defaults:

| Variable | Default |
|---|---|
| `region` | `eu-central-1` |
| `github_repository` | `jbiscella/H-tchen-Mail` |
| `deploy_branch` | `main` |
| `state_bucket_name` | `monitoring-tfstate` |
| `lock_table_name` | `monitoring-tflock` |
| `deploy_role_name` | `gh-actions-monitoring-deploy` |

## After the apply

Capture the outputs:

```bash
terraform output -json > bootstrap-outputs.json
```

Then in `terraform/main` (lands in 9c) configure the backend with the
bucket / table from this stack, and in the GitHub Actions workflow
add the deploy role ARN as the `role-to-assume` input to
`aws-actions/configure-aws-credentials`.

## Trust policy details

The deploy role's trust policy restricts assumption to two subject
patterns:

```
repo:<owner>/<repo>:ref:refs/heads/<branch>
repo:<owner>/<repo>:pull_request
```

The first matches the `terraform-apply` job on push-to-main; the
second matches `terraform-plan` on pull requests. If you decide that
PRs from forks should *not* be able to assume even a plan-only role,
narrow the second subject (e.g. require `head_ref` to start with a
specific prefix) — by default GitHub Actions `pull_request` runs on
forked PRs use the same OIDC issuer.
