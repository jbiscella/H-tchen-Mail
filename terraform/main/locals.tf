data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.id

  tags = {
    Project     = "monitoring"
    ManagedBy   = "terraform"
    Environment = "prod"
    Stack       = "main"
  }

  # --- Supported exchanges -------------------------------------------------
  # The single source of truth for which exchanges the app will accept on
  # instrument registration. Lambdas read these via env vars set in
  # lambda.tf; ssm.tf mirrors them for operator-facing inventory.
  # Extending the set is a config-only change: edit the map below, push,
  # CI redeploys the Lambdas with new env vars.

  exchange_suffix_map = {
    NASDAQ = ""
    NYSE   = ""
    MIL    = ".MI"
    XETRA  = ".DE"
    LSE    = ".L"
    TSX    = ".TO"
    PAR    = ".PA"
    AMS    = ".AS"
    SWX    = ".SW" # SIX Swiss Exchange (Richemont, Nestlé, …)
    BME    = ".MC" # Bolsa de Madrid (Amadeus IT Group, Iberdrola, …)
  }

  supported_exchanges_csv  = join(",", keys(local.exchange_suffix_map))
  exchange_suffix_map_json = jsonencode(local.exchange_suffix_map)
}
