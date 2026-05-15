# --- SSM parameters --------------------------------------------------------
# Operational documentation of the project's tunables under /monitoring/*
# per CLAUDE.md §14. **The Lambdas do NOT read these at startup** — runtime
# config comes from application.yml + Lambda environment.variables (set in
# lambda.tf). The SSM catalog stays here as:
#
#   1. a single console-browsable inventory operators can scan;
#   2. a place to put the SecureString sender-email value (populated
#      manually via aws ssm put-parameter — Terraform does not own that
#      value because a `terraform plan` diff would leak it);
#   3. a forward-compatible hook if we ever wire up
#      micronaut-aws-parameter-store to overlay these at startup.

locals {
  ssm_string_params = {
    "/monitoring/market-data/provider"              = "yahoo"
    "/monitoring/market-data/yahoo/timeout-seconds" = "10"
    "/monitoring/market-data/yahoo/max-rps"         = "1"

    "/monitoring/bootstrap/size-1d" = "250"
    "/monitoring/bootstrap/size-1w" = "260"

    "/monitoring/exchanges/supported"  = local.supported_exchanges_csv
    "/monitoring/exchanges/suffix-map" = local.exchange_suffix_map_json

    "/monitoring/ingest/circuit-breaker.threshold" = "3"
    "/monitoring/ingest/failure-rate-alert"        = "0.5"

    "/monitoring/bedrock/model-id"             = var.bedrock_model_id
    "/monitoring/bedrock/region"               = var.region
    "/monitoring/bedrock/max-tokens"           = "1500"
    "/monitoring/bedrock/max-tool-iterations"  = "8"
    "/monitoring/bedrock/tool-timeout-seconds" = "5"

    "/monitoring/chart/lookback-bars" = "30"
    "/monitoring/chart/width-px"      = "900"
    "/monitoring/chart/height-px"     = "500"
    "/monitoring/chart/show-volume"   = "false"

    "/monitoring/email/subject-prefix" = "[HA Alert]"
    "/monitoring/email/charset"        = "UTF-8"

    "/monitoring/ses/region"   = var.ses_region
    "/monitoring/ses/reply-to" = "var.ses_sender_email"

    "/monitoring/alerts/audit-enabled" = "false"

    "/monitoring/retry/max-attempts"            = "3"
    "/monitoring/retry/delay-seconds"           = "3600"
    "/monitoring/retry/poller-interval-minutes" = "15"

    "/monitoring/dynamodb/table-name" = aws_dynamodb_table.monitoring.name
    "/monitoring/dynamodb/gsi1-name"  = var.gsi1_name
    "/monitoring/dynamodb/gsi2-name"  = var.gsi2_name
  }
}

resource "aws_ssm_parameter" "config" {
  for_each = local.ssm_string_params

  name  = each.key
  type  = "String"
  value = each.value

  # Allow operators to tweak values from the console without TF clobbering
  # them on next apply.
  lifecycle {
    ignore_changes = [value]
  }
}
