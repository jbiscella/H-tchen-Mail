# --- monitoring single-table -----------------------------------------------
# Per CLAUDE.md §2: pk/sk on the base table; sparse GSI1 (status → instrument
# id) for active/archived listing; sparse GSI2 (RETRY_DUE → retry_at_iso) for
# the pending-alert poller.

resource "aws_dynamodb_table" "monitoring" {
  name         = var.table_name
  billing_mode = "PAY_PER_REQUEST"

  # Base-table hash_key / range_key are NOT deprecated in the 6.45.0 provider we
  # pin, and it exposes no top-level key_schema block — verified against
  # `terraform providers schema -json`, not the registry docs, which document the
  # unreleased `main` branch where a top-level key_schema does exist. Only the
  # GSI-level arguments are deprecated (see the key_schema blocks below).
  hash_key  = "pk"
  range_key = "sk"

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

  # GSI keys use key_schema: hash_key / range_key are deprecated *inside*
  # global_secondary_index in provider 6.x (these two blocks were the two
  # "hash_key is deprecated" warnings on the plan — the warning anchors to the
  # resource block, which makes it read as though the base table were at fault).
  #
  # Two provider bugs previously made this migration unsafe:
  # hashicorp/terraform-provider-aws#46601 (removing one GSI deleted and
  # recreated ALL of them) and #46513 (perpetual drift, because the AWS API
  # returns GSI keys in the old shape). #46601 was fixed in 6.37.0 and we pin
  # 6.45.0, so both are behind us — but a plan must still be read before an
  # apply: a GSI change means minutes of failed queries on that index, unlike a
  # no-op.
  global_secondary_index {
    name            = var.gsi1_name
    projection_type = "ALL"

    key_schema {
      attribute_name = "gsi1Pk"
      key_type       = "HASH"
    }
    key_schema {
      attribute_name = "gsi1Sk"
      key_type       = "RANGE"
    }
  }

  global_secondary_index {
    name            = var.gsi2_name
    projection_type = "ALL"

    key_schema {
      attribute_name = "gsi2Pk"
      key_type       = "HASH"
    }
    key_schema {
      attribute_name = "gsi2Sk"
      key_type       = "RANGE"
    }
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
