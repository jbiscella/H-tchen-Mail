# --- EventBridge schedules -------------------------------------------------
# Daily run of monitoring-main at 03:00 UTC (after EODHD's nightly EOD batch —
# see the rule below for the measurement) and a 15-minute drumbeat for
# retry-poller. Both target
# the function's `live` alias so a deploy that misbehaves can be rolled back
# by just repointing the alias — no schedule change needed.

# --- monitoring-main: daily at 03:00 UTC, after EODHD publishes -------------
#
# Was 22:00 UTC, which is too early and had been silently running a day behind.
# Measured 2026-08-12/13: at 22:00 the 12 Aug bars did not exist yet
# (bars_inserted=0, so no detection and no email); by 00:35 UTC all four
# instruments had them. Runs that appeared healthy at 22:00 were ingesting the
# PREVIOUS day's bars, still unclaimed — which looks identical to working.
#
# The floor is the last close plus EODHD's publication lag. NVDA closes 20:00 UTC
# (21:00 in winter), and the observed batch lands between 22:00 and 00:35. 03:00
# leaves 2.5-5h of margin and absorbs the winter shift.
#
# 03:00 also covers every other main market, because EODHD publishes one nightly
# batch across exchanges rather than per-exchange as each closes: verified at
# 00:45 UTC that 0005.HK, BHP.AU, SAP.XETRA and NVDA.US all already carried their
# 12 Aug bar. Known limitation, and a vendor one rather than a scheduling one:
# Tokyo and Sydney open at 00:00 UTC, before that batch lands, so an
# Asia-Pacific daily alert can only ever arrive during the next session.
resource "aws_cloudwatch_event_rule" "main_daily" {
  name                = "monitoring-daily"
  description         = "Triggers monitoring-main at 03:00 UTC daily, after EODHD's nightly EOD batch."
  schedule_expression = "cron(0 3 * * ? *)"
}

resource "aws_cloudwatch_event_target" "main_daily" {
  rule = aws_cloudwatch_event_rule.main_daily.name
  arn  = aws_lambda_alias.main_live.arn
}

resource "aws_lambda_permission" "main_daily" {
  statement_id  = "AllowEventBridgeInvokeMainDaily"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.main.function_name
  qualifier     = aws_lambda_alias.main_live.name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.main_daily.arn
}

# --- retry-poller: every 15 minutes ----------------------------------------

resource "aws_cloudwatch_event_rule" "retry_poller" {
  name                = "monitoring-retry-poller"
  description         = "Triggers retry-poller every 15 minutes."
  schedule_expression = "cron(0/15 * * * ? *)"
}

resource "aws_cloudwatch_event_target" "retry_poller" {
  rule = aws_cloudwatch_event_rule.retry_poller.name
  arn  = aws_lambda_alias.retry_live.arn
}

resource "aws_lambda_permission" "retry_poller" {
  statement_id  = "AllowEventBridgeInvokeRetryPoller"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.retry.function_name
  qualifier     = aws_lambda_alias.retry_live.name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.retry_poller.arn
}
