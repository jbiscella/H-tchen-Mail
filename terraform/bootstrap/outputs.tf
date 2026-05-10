output "tfstate_bucket" {
  description = "S3 bucket holding the main-stack Terraform state."
  value       = aws_s3_bucket.tfstate.bucket
}

output "tflock_table" {
  description = "DynamoDB table holding the Terraform state lock."
  value       = aws_dynamodb_table.tflock.name
}

output "github_oidc_provider_arn" {
  description = "ARN of the GitHub Actions OIDC provider."
  value       = aws_iam_openid_connect_provider.github.arn
}

output "deploy_role_arn" {
  description = "Role ARN GitHub Actions assumes for plan / apply / deploy. Wire this into the workflow as the 'role-to-assume' input."
  value       = aws_iam_role.deploy.arn
}
