# --- Lambda execution roles ------------------------------------------------
# Both monitoring-main and retry-poller share an identical permission scope
# per CLAUDE.md §12 — same DynamoDB / Bedrock / SES / SSM / KMS / SNS / Logs /
# CloudWatch Metrics. Two distinct roles so they can be revoked independently
# in an incident.

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

# Permissions document, shared between both roles.
data "aws_iam_policy_document" "lambda_perms" {
  # DynamoDB R/W on the project table + both GSIs.
  statement {
    sid    = "DynamoDBTable"
    effect = "Allow"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:DeleteItem",
      "dynamodb:Query",
      "dynamodb:BatchWriteItem",
      "dynamodb:BatchGetItem",
      "dynamodb:TransactWriteItems",
      "dynamodb:TransactGetItems",
      "dynamodb:DescribeTable",
    ]
    resources = [
      aws_dynamodb_table.monitoring.arn,
      "${aws_dynamodb_table.monitoring.arn}/index/${var.gsi1_name}",
      "${aws_dynamodb_table.monitoring.arn}/index/${var.gsi2_name}",
    ]
  }

  # Bedrock invocation on the configured model.
  statement {
    sid    = "BedrockInvoke"
    effect = "Allow"
    actions = [
      "bedrock:InvokeModel",
      "bedrock:Converse",
      "bedrock:ConverseStream",
    ]
    resources = [
      "arn:aws:bedrock:${var.region}::foundation-model/${var.bedrock_model_id}",
      # Inference profiles for the same model — used in some accounts.
      "arn:aws:bedrock:${var.region}:${local.account_id}:inference-profile/*",
    ]
  }

  # SES v2 sendEmail / SendRawEmail on the verified identity.
  statement {
    sid    = "SESSend"
    effect = "Allow"
    actions = [
      "ses:SendEmail",
      "ses:SendRawEmail",
    ]
    resources = [
      "arn:aws:ses:${var.ses_region}:${local.account_id}:identity/${var.ses_sender_email}",
    ]
  }

  # SSM parameters under /monitoring/* (incl. SecureString sender email).
  statement {
    sid    = "SSMParameters"
    effect = "Allow"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]
    resources = ["arn:aws:ssm:${var.region}:${local.account_id}:parameter/monitoring/*"]
  }

  # KMS Decrypt for the AWS-managed SSM key (SecureString parameters).
  statement {
    sid       = "KMSDecryptForSSM"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${var.region}.amazonaws.com"]
    }
  }

  # SNS publish to the DLQ.
  statement {
    sid       = "SNSPublishDLQ"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.dlq.arn]
  }

  # CloudWatch Logs (auto-created log groups; explicit allow makes them work
  # even if the function fires before the log group resource is provisioned).
  statement {
    sid    = "CloudWatchLogs"
    effect = "Allow"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = ["arn:aws:logs:${var.region}:${local.account_id}:log-group:/aws/lambda/*"]
  }

  # Custom-namespace metrics.
  statement {
    sid       = "CloudWatchMetrics"
    effect    = "Allow"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = ["Monitoring/HeikinAshi"]
    }
  }
}

resource "aws_iam_policy" "lambda_perms" {
  name        = "monitoring-lambda-permissions"
  description = "Shared by monitoring-main and retry-poller Lambdas."
  policy      = data.aws_iam_policy_document.lambda_perms.json
}

# --- monitoring-main role --------------------------------------------------

resource "aws_iam_role" "main" {
  name               = "${var.lambda_main_name}-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

resource "aws_iam_role_policy_attachment" "main_perms" {
  role       = aws_iam_role.main.name
  policy_arn = aws_iam_policy.lambda_perms.arn
}

# --- retry-poller role -----------------------------------------------------

resource "aws_iam_role" "retry" {
  name               = "${var.lambda_retry_name}-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

resource "aws_iam_role_policy_attachment" "retry_perms" {
  role       = aws_iam_role.retry.name
  policy_arn = aws_iam_policy.lambda_perms.arn
}
