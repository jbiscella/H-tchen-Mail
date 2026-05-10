# --- CloudWatch alarms -----------------------------------------------------
# Alarms publish to monitoring-ops-alerts. Names + thresholds match
# CLAUDE.md §14 "Observability — alarms".

# --- MainNotRunning --------------------------------------------------------
# No invocation of monitoring-main in the last 26 hours. Cron is daily, so
# 26h gives ~2h of grace on top of the schedule before this fires.

resource "aws_cloudwatch_metric_alarm" "main_not_running" {
  alarm_name          = "MainNotRunning"
  alarm_description   = "monitoring-main has not been invoked in the last 26 hours."
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Invocations"
  namespace           = "AWS/Lambda"
  period              = 26 * 3600
  statistic           = "Sum"
  threshold           = 1
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.ops_alerts.arn]
  ok_actions          = [aws_sns_topic.ops_alerts.arn]

  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
    Resource     = "${aws_lambda_function.main.function_name}:${aws_lambda_alias.main_live.name}"
  }
}

# --- MainFailureRate -------------------------------------------------------
# > 30% of instruments failed during a run. Uses the custom-namespace
# metrics emitted by the handler (CLAUDE.md §14).

resource "aws_cloudwatch_metric_alarm" "main_failure_rate" {
  alarm_name          = "MainFailureRate"
  alarm_description   = "InstrumentsFailed / InstrumentsProcessed > 30% on monitoring-main."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = 0.30
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.ops_alerts.arn]

  metric_query {
    id          = "rate"
    expression  = "IF(processed > 0, failed / processed, 0)"
    label       = "Instrument failure rate"
    return_data = true
  }

  metric_query {
    id = "failed"
    metric {
      namespace   = "Monitoring/HeikinAshi"
      metric_name = "InstrumentsFailed"
      period      = 86400
      stat        = "Sum"
      dimensions  = { Run = "main" }
    }
  }

  metric_query {
    id = "processed"
    metric {
      namespace   = "Monitoring/HeikinAshi"
      metric_name = "InstrumentsProcessed"
      period      = 86400
      stat        = "Sum"
      dimensions  = { Run = "main" }
    }
  }
}

# --- RetryBacklog ----------------------------------------------------------
# Per-poller-run count of items processed; stays high if the queue is
# growing faster than the poller can drain it.

resource "aws_cloudwatch_metric_alarm" "retry_backlog" {
  alarm_name          = "RetryBacklog"
  alarm_description   = "retry-poller processed > 50 items in a single run — backlog growing."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "RetryItemsProcessed"
  namespace           = "Monitoring/HeikinAshi"
  period              = 900
  statistic           = "Maximum"
  threshold           = 50
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.ops_alerts.arn]
}

# --- BedrockHighErrorRate --------------------------------------------------
# Bedrock has been throwing > 10 LLMException/hour — usually a model-access
# or quota issue. Counted via Lambda log-metric-filter equivalent: we rely
# on the AlertsQueuedForRetry counter (which fires on AI failure too) as a
# coarse proxy. Tighten with a real log filter when ops asks for it.

resource "aws_cloudwatch_metric_alarm" "bedrock_high_error_rate" {
  alarm_name          = "BedrockHighErrorRate"
  alarm_description   = "AlertsQueuedForRetry > 10/h — likely Bedrock or chart failures."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "AlertsQueuedForRetry"
  namespace           = "Monitoring/HeikinAshi"
  period              = 3600
  statistic           = "Sum"
  threshold           = 10
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.ops_alerts.arn]
}

# --- LambdaErrors ----------------------------------------------------------
# Any Lambda error counts: this is the catch-all for the cases that didn't
# get handled by the in-handler try/catch and bubbled out.

resource "aws_cloudwatch_metric_alarm" "lambda_errors_main" {
  alarm_name          = "LambdaErrors-${aws_lambda_function.main.function_name}"
  alarm_description   = "Unhandled errors in monitoring-main."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Errors"
  namespace           = "AWS/Lambda"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.ops_alerts.arn]

  dimensions = {
    FunctionName = aws_lambda_function.main.function_name
  }
}

resource "aws_cloudwatch_metric_alarm" "lambda_errors_retry" {
  alarm_name          = "LambdaErrors-${aws_lambda_function.retry.function_name}"
  alarm_description   = "Unhandled errors in retry-poller."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Errors"
  namespace           = "AWS/Lambda"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.ops_alerts.arn]

  dimensions = {
    FunctionName = aws_lambda_function.retry.function_name
  }
}

# --- DLQDepth --------------------------------------------------------------
# Anything in the DLQ is a hard fail to investigate. Single message trips it.

resource "aws_cloudwatch_metric_alarm" "dlq_depth" {
  alarm_name          = "DLQDepth"
  alarm_description   = "monitoring-dlq has at least one message."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "NumberOfMessagesPublished"
  namespace           = "AWS/SNS"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.ops_alerts.arn]

  dimensions = {
    TopicName = aws_sns_topic.dlq.name
  }
}
