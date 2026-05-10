# --- EventBridge schedules -------------------------------------------------
# Daily run of monitoring-main at 22:00 UTC (after US market close + EU
# 1d/1w bars closed) and a 15-minute drumbeat for retry-poller. Both target
# the function's `live` alias so a deploy that misbehaves can be rolled back
# by just repointing the alias — no schedule change needed.

# --- monitoring-main: daily at 22:00 UTC -----------------------------------

resource "aws_cloudwatch_event_rule" "main_daily" {
  name                = "monitoring-daily"
  description         = "Triggers monitoring-main at 22:00 UTC daily."
  schedule_expression = "cron(0 22 * * ? *)"
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
