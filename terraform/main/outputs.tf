# Wired into the GitHub Actions deploy job (jar update + alias bump) and
# kept handy for ops debugging.

output "dynamodb_table_name" {
  description = "Name of the single-table monitoring DynamoDB table."
  value       = aws_dynamodb_table.monitoring.name
}

output "dynamodb_table_arn" {
  value = aws_dynamodb_table.monitoring.arn
}

output "artifacts_bucket_name" {
  description = "S3 bucket the CI deploy job uploads the Lambda jar to."
  value       = aws_s3_bucket.artifacts.bucket
}

output "lambda_main_arn" {
  value = aws_lambda_function.main.arn
}

output "lambda_main_name" {
  value = aws_lambda_function.main.function_name
}

output "lambda_main_alias_arn" {
  description = "ARN of the `live` alias — what EventBridge invokes."
  value       = aws_lambda_alias.main_live.arn
}

output "lambda_retry_arn" {
  value = aws_lambda_function.retry.arn
}

output "lambda_retry_name" {
  value = aws_lambda_function.retry.function_name
}

output "lambda_retry_alias_arn" {
  value = aws_lambda_alias.retry_live.arn
}

output "sns_dlq_arn" {
  value = aws_sns_topic.dlq.arn
}

output "sns_ops_alerts_arn" {
  value = aws_sns_topic.ops_alerts.arn
}

output "ses_sender_identity_arn" {
  description = "Verified SES identity ARN (cross-region). Wired into the Lambda IAM policy."
  value       = aws_sesv2_email_identity.sender.arn
}
