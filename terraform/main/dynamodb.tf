# --- monitoring single-table -----------------------------------------------
# Per CLAUDE.md §2: pk/sk on the base table; sparse GSI1 (status → instrument
# id) for active/archived listing; sparse GSI2 (RETRY_DUE → retry_at_iso) for
# the pending-alert poller.

resource "aws_dynamodb_table" "monitoring" {
  name         = var.table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  range_key    = "sk"

  attribute {
    name = "pk"
    type = "S"
  }
  attribute {
    name = "sk"
    type = "S"
  }
  attribute {
    name = "gsi1Pk"
    type = "S"
  }
  attribute {
    name = "gsi1Sk"
    type = "S"
  }
  attribute {
    name = "gsi2Pk"
    type = "S"
  }
  attribute {
    name = "gsi2Sk"
    type = "S"
  }

  global_secondary_index {
    name            = var.gsi1_name
    hash_key        = "gsi1Pk"
    range_key       = "gsi1Sk"
    projection_type = "ALL"
  }

  global_secondary_index {
    name            = var.gsi2_name
    hash_key        = "gsi2Pk"
    range_key       = "gsi2Sk"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  server_side_encryption {
    enabled = true
  }

  point_in_time_recovery {
    enabled = true
  }
}
