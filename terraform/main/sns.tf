# --- DLQ + ops alerts topics -----------------------------------------------
# Both Lambdas point their on-failure DLQ at monitoring-dlq. CloudWatch alarms
# (alarms.tf) publish to monitoring-ops-alerts. Subscribers (email / Slack
# webhook / etc) are wired up out-of-band.

resource "aws_sns_topic" "dlq" {
  name = "monitoring-dlq"
}

resource "aws_sns_topic" "ops_alerts" {
  name = "monitoring-ops-alerts"
}
