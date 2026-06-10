Feature: SI-3c.3 — Strategy alert retry

  Executable counterpart of CLAUDE.md §9 Component 1c "Retry for strategy
  alerts": a failed strategy dispatch enqueues a STRATEGY_PENDING_ALERT (§2)
  storing only the alert JSON (never the chart). The StrategyRetryPollerService
  re-renders the chart from the persisted Strategy + bars (chart-degraded if the
  strategy is gone), re-runs the AI analyst, re-sends, audits on success, and
  drops the poison item when all recipients are rejected on the final attempt.
  The live DynamoDB round-trip is covered by
  DynamoDbPendingStrategyAlertRepositoryIT (LocalStack).

  Background:
    Given the registry is empty
    And current UTC time is "2026-05-07T22:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS"
    And an instrument "AAPL" on "NASDAQ" already exists
    And the recipients for "AAPL" are "alice@example.com"

  Scenario: A transient AI failure on first attempt enqueues a strategy pending alert
    Given a strategy alert for "AAPL" with a "long_entry" scenario "oversold-entry"
    And the AI analyst will fail the next 1 calls
    When the strategy alert is dispatched
    Then no strategy email is sent
    And a strategy pending alert is enqueued with retry_count 0
    And the strategy dispatch counts queued 1

  Scenario: The poller re-renders the chart and re-sends
    Given a strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 0 due now
    When the strategy retry poller runs
    Then the strategy chart is re-rendered from the persisted strategy
    And a full strategy email is sent to "alice@example.com"
    And the strategy pending alert is deleted

  Scenario: Retry renders from the strategy that fired, not the live re-imported one
    Given a strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 0 due now carrying strategy snapshot "fired-strategy"
    When the strategy retry poller runs
    Then the re-rendered chart used strategy "fired-strategy"
    And a full strategy email is sent to "alice@example.com"
    And the strategy pending alert is deleted

  Scenario: Retry restores the trigger bar evicted by retention so the chart still renders
    Given a strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 0 due now carrying its trigger bar
    When the strategy retry poller runs
    Then the re-rendered chart includes the trigger bar
    And a full strategy email is sent to "alice@example.com"
    And the strategy pending alert is deleted

  Scenario: A missing strategy at retry degrades to a chart-less email after the cap
    Given no strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 2 due now
    When the strategy retry poller runs
    Then a degraded strategy email is sent without a chart
    And the strategy pending alert is deleted

  Scenario: A mail outage on retry is recorded against the email component, not AI
    Given a strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 0 due now
    And the email sender is unavailable
    When the strategy retry poller runs
    Then no strategy email is sent
    And the strategy pending alert retry_count is 1
    And the strategy pending alert last_error component is "email"

  Scenario: An audit-write failure after a successful retry send still deletes the pending
    Given audit logging is enabled
    And a strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 0 due now
    And the audit write will fail
    When the strategy retry poller runs
    Then a full strategy email is sent to "alice@example.com"
    And the strategy pending alert is deleted

  Scenario: A transient failure under the cap bumps the retry count
    Given a strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 0 due now
    And the AI analyst will fail the next 1 calls
    When the strategy retry poller runs
    Then no strategy email is sent
    And the strategy pending alert retry_count is 1

  Scenario: All recipients rejected on the final attempt drops the poison item
    Given a strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 2 due now
    And the email sender will reject recipient "alice@example.com"
    When the strategy retry poller runs
    Then the strategy pending alert is deleted
