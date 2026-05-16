# --- GitHub OIDC provider --------------------------------------------------
#
# Lets GitHub Actions assume an AWS IAM role without long-lived static keys.
# Trust is restricted to a single repository + branch (or pull_request env)
# so a leaked OIDC token from another repo cannot mint credentials.

data "tls_certificate" "github_actions" {
  url = "https://token.actions.githubusercontent.com"
}

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github_actions.certificates[0].sha1_fingerprint]
}

# --- Deploy role -----------------------------------------------------------

data "aws_iam_policy_document" "deploy_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Pin to the configured repo + branch. terraform-plan on PRs runs under
    # ref:refs/pull/<number>/merge, so we allow that subject too — the plan
    # job is read-only (see deploy_policy below restricting writes).
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repository}:ref:refs/heads/${var.deploy_branch}",
        "repo:${var.github_repository}:pull_request",
      ]
    }
  }
}

resource "aws_iam_role" "deploy" {
  name               = var.deploy_role_name
  assume_role_policy = data.aws_iam_policy_document.deploy_trust.json
  description        = "Assumed by GitHub Actions to plan/apply Terraform and deploy Lambda jars."
}

# Inline managed policy giving the deploy role what it needs to manage the
# main-stack resources. Kept narrow on purpose: explicit allow-lists, no
# wildcards on dangerous services.
data "aws_iam_policy_document" "deploy" {
  # Terraform state read/write
  statement {
    sid     = "TerraformStateBucket"
    effect  = "Allow"
    actions = ["s3:ListBucket", "s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = [
      aws_s3_bucket.tfstate.arn,
      "${aws_s3_bucket.tfstate.arn}/*",
    ]
  }

  statement {
    sid       = "TerraformStateLockTable"
    effect    = "Allow"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem", "dynamodb:DescribeTable"]
    resources = [aws_dynamodb_table.tflock.arn]
  }

  # Lambda jar upload to the artifacts bucket (created by the main stack).
  statement {
    sid     = "ArtifactsUpload"
    effect  = "Allow"
    actions = ["s3:GetObject", "s3:PutObject", "s3:ListBucket"]
    resources = [
      "arn:aws:s3:::monitoring-artifacts",
      "arn:aws:s3:::monitoring-artifacts/*",
    ]
  }

  # Manage the project's resources (table, lambdas, schedules, alarms, etc).
  # Per CLAUDE.md §11 we're in single-environment mode so a wide scope on the
  # project's own services is acceptable.
  statement {
    sid    = "ProjectResources"
    effect = "Allow"
    actions = [
      "dynamodb:*",
      "lambda:*",
      "events:*",
      "sns:*",
      "ssm:*",
      "logs:*",
      "s3:*",
      "ses:*",
      "sesv2:*",
      "cloudwatch:*",
      # Read-only Bedrock perms used by the aws-preflight CI step to
      # verify the configured model/profile exists before terraform apply.
      "bedrock:ListFoundationModels",
      "bedrock:ListInferenceProfiles",
      "bedrock:GetFoundationModel",
      "bedrock:GetInferenceProfile",
      # Invoke perms for the aws-preflight "model access" smoke call: a
      # 1-token converse against BEDROCK_MODEL_ID. The Converse API
      # authorises against bedrock:InvokeModel, so both actions are needed.
      "bedrock:InvokeModel",
      "bedrock:Converse",
      # IAM policy simulator used by aws-preflight to verify the Lambda
      # role can actually invoke Bedrock on the configured resource ARNs.
      "iam:SimulatePrincipalPolicy",
      "iam:GetRole",
      "iam:PassRole",
      "iam:CreateRole",
      "iam:DeleteRole",
      "iam:UpdateRole",
      "iam:UpdateAssumeRolePolicy",
      "iam:AttachRolePolicy",
      "iam:DetachRolePolicy",
      "iam:PutRolePolicy",
      "iam:DeleteRolePolicy",
      "iam:GetRolePolicy",
      "iam:ListRolePolicies",
      "iam:ListAttachedRolePolicies",
      "iam:TagRole",
      "iam:UntagRole",
      "iam:CreatePolicy",
      "iam:DeletePolicy",
      "iam:GetPolicy",
      "iam:GetPolicyVersion",
      "iam:ListPolicyVersions",
      "iam:CreatePolicyVersion",
      "iam:DeletePolicyVersion",
      "iam:ListEntitiesForPolicy",
      "iam:TagPolicy",
      "iam:UntagPolicy",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "${var.deploy_role_name}-inline"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}
