terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source                = "hashicorp/aws"
      version               = "~> 6.21"
      configuration_aliases = [aws.ses]
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
  }

  # State lives in the bootstrap-stack-provisioned S3 bucket. The actual
  # bucket / table names come from the bootstrap outputs and are wired in
  # via the workflow's `terraform init -backend-config=...` flags so a
  # different account can repoint without forking this file.
  backend "s3" {
    key          = "monitoring/main.tfstate"
    encrypt      = true
    use_lockfile = false # legacy DynamoDB lock — set via -backend-config dynamodb_table=...
  }
}

# Compute / data plane region.
provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}

# SES identity lives in a different region per CLAUDE.md §1 ADR. Most projects
# use us-east-1; this one is configured for eu-south-1 by default but it stays
# overridable.
provider "aws" {
  alias  = "ses"
  region = var.ses_region

  default_tags {
    tags = local.tags
  }
}
