variable "region" {
  description = "AWS region for the bootstrap resources (state bucket + lock table). Compute lives here too in §12."
  type        = string
  default     = "eu-central-1"
}

variable "github_repository" {
  description = "GitHub repository allowed to assume the deploy role via OIDC, in <owner>/<repo> form."
  type        = string
  default     = "jbiscella/H-tchen-Mail"
}

variable "deploy_branch" {
  description = "Git branch on which the deploy role can be assumed. The trust policy pins to refs/heads/<branch>."
  type        = string
  default     = "main"
}

variable "state_bucket_name" {
  description = "Name of the S3 bucket holding the Terraform main-stack state."
  type        = string
  default     = "monitoring-tfstate"
}

variable "lock_table_name" {
  description = "Name of the DynamoDB table holding the Terraform state lock."
  type        = string
  default     = "monitoring-tflock"
}

variable "deploy_role_name" {
  description = "Name of the IAM role assumed by GitHub Actions for plan/apply/deploy."
  type        = string
  default     = "gh-actions-monitoring-deploy"
}
