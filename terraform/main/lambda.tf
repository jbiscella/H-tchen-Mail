# --- Stub jar for the initial create ---------------------------------------
# Terraform creates the Lambda functions with this 1KB placeholder, then
# CI uploads the real jar to S3 and points each function at the new code via
# `aws lambda update-function-code` + `publish-version` + `update-alias`.
# Lifecycle blocks below ignore subsequent code drift so terraform apply
# does not undo CI's deploy.

data "archive_file" "stub_jar" {
  type        = "zip"
  output_path = "${path.module}/files/stub.zip"

  source {
    content  = "Manifest-Version: 1.0\n"
    filename = "META-INF/MANIFEST.MF"
  }
}

# --- monitoring-main -------------------------------------------------------

resource "aws_cloudwatch_log_group" "main" {
  name              = "/aws/lambda/${var.lambda_main_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "main" {
  function_name = var.lambda_main_name
  role          = aws_iam_role.main.arn
  handler       = var.lambda_main_handler
  runtime       = var.lambda_runtime
  architectures = ["arm64"]

  filename         = data.archive_file.stub_jar.output_path
  source_code_hash = data.archive_file.stub_jar.output_base64sha256

  memory_size = var.lambda_memory_mb
  timeout     = var.lambda_main_timeout_seconds

  reserved_concurrent_executions = 1
  publish                        = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  dead_letter_config {
    target_arn = aws_sns_topic.dlq.arn
  }

  environment {
    variables = {
      LOG_LEVEL                     = "INFO"
      MONITORING_TABLE              = aws_dynamodb_table.monitoring.name
      MONITORING_EMAIL_SENDER_EMAIL = var.ses_sender_email
      MONITORING_EODHD_API_KEY      = var.eodhd_api_key
      MONITORING_BEDROCK_MODEL_ID   = var.bedrock_model_id
      MONITORING_SES_REGION         = var.ses_region
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.main_perms,
    aws_cloudwatch_log_group.main,
  ]

  lifecycle {
    ignore_changes = [
      filename,
      source_code_hash,
      s3_bucket,
      s3_key,
      s3_object_version,
    ]
  }
}

resource "aws_lambda_alias" "main_live" {
  name             = "live"
  function_name    = aws_lambda_function.main.function_name
  function_version = aws_lambda_function.main.version

  lifecycle {
    ignore_changes = [function_version]
  }
}

# --- retry-poller ----------------------------------------------------------

resource "aws_cloudwatch_log_group" "retry" {
  name              = "/aws/lambda/${var.lambda_retry_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "retry" {
  function_name = var.lambda_retry_name
  role          = aws_iam_role.retry.arn
  handler       = var.lambda_retry_handler
  runtime       = var.lambda_runtime
  architectures = ["arm64"]

  filename         = data.archive_file.stub_jar.output_path
  source_code_hash = data.archive_file.stub_jar.output_base64sha256

  memory_size = var.lambda_memory_mb
  timeout     = var.lambda_retry_timeout_seconds

  reserved_concurrent_executions = 1
  publish                        = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  dead_letter_config {
    target_arn = aws_sns_topic.dlq.arn
  }

  environment {
    variables = {
      LOG_LEVEL                     = "INFO"
      MONITORING_TABLE              = aws_dynamodb_table.monitoring.name
      MONITORING_EMAIL_SENDER_EMAIL = var.ses_sender_email
      MONITORING_EODHD_API_KEY      = var.eodhd_api_key
      MONITORING_BEDROCK_MODEL_ID   = var.bedrock_model_id
      MONITORING_SES_REGION         = var.ses_region
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.retry_perms,
    aws_cloudwatch_log_group.retry,
  ]

  lifecycle {
    ignore_changes = [
      filename,
      source_code_hash,
      s3_bucket,
      s3_key,
      s3_object_version,
    ]
  }
}

resource "aws_lambda_alias" "retry_live" {
  name             = "live"
  function_name    = aws_lambda_function.retry.function_name
  function_version = aws_lambda_function.retry.version

  lifecycle {
    ignore_changes = [function_version]
  }
}
