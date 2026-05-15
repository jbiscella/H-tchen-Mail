variable "region" {
  description = "AWS region for compute and DynamoDB."
  type        = string
  default     = "eu-central-1"
}

variable "ses_region" {
  description = "AWS region for the SES identity (separate per CLAUDE.md §1)."
  type        = string
  default     = "eu-central-1"
}

variable "table_name" {
  description = "DynamoDB single-table name."
  type        = string
  default     = "monitoring"
}

variable "gsi1_name" {
  description = "Name of the gsi_status sparse GSI."
  type        = string
  default     = "gsi_status"
}

variable "gsi2_name" {
  description = "Name of the gsi_retry_due sparse GSI."
  type        = string
  default     = "gsi_retry_due"
}

variable "lambda_main_name" {
  description = "Name of the daily-pipeline Lambda."
  type        = string
  default     = "monitoring-main"
}

variable "lambda_retry_name" {
  description = "Name of the retry-poller Lambda."
  type        = string
  default     = "retry-poller"
}

variable "lambda_runtime" {
  description = "AWS Lambda managed runtime. ADR pins to Java 25 + SnapStart."
  type        = string
  default     = "java25"
}

variable "lambda_main_timeout_seconds" {
  description = "Per CLAUDE.md §10 — daily run is allowed up to 15 minutes."
  type        = number
  default     = 900
}

variable "lambda_retry_timeout_seconds" {
  description = "Per CLAUDE.md §10 — retry poller is allowed up to 5 minutes."
  type        = number
  default     = 300
}

variable "lambda_memory_mb" {
  description = "Memory size for both Lambdas."
  type        = number
  default     = 1024
}

variable "lambda_main_handler" {
  description = "Java handler class for monitoring-main."
  type        = string
  default     = "com.heikinashi.monitoring.orchestration.MonitoringMainHandler"
}

variable "lambda_retry_handler" {
  description = "Java handler class for retry-poller."
  type        = string
  default     = "com.heikinashi.monitoring.orchestration.RetryPollerHandler"
}

variable "ses_sender_email" {
  description = "SES verified sender. SES production access must be requested manually."
  type        = string
  default     = "alerts@example.com"
}

variable "bedrock_model_id" {
  description = "Bedrock model id used by the AI analyst."
  type        = string
  default     = "anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "eodhd_api_key" {
  description = "EODHD API token. Populated from the EODHD_KEY GitHub secret via TF_VAR_eodhd_api_key. Never default — boot fails fast if missing."
  type        = string
  sensitive   = true
}

variable "log_retention_days" {
  description = "CloudWatch log group retention for both Lambdas."
  type        = number
  default     = 30
}

variable "artifacts_bucket_name" {
  description = "Globally unique name for the Lambda artifacts S3 bucket."
  type        = string
  default     = "monitoring-artifacts"
}