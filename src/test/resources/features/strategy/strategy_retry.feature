Feature: SI-3c.3 — Strategy alert retry

  Executable counterpart of CLAUDE.md §9 Component 1c "Retry for strategy
  alerts": a failed strategy dispatch enqueues a STRATEGY_PENDING_ALERT (§2)
  storing the already-rendered chart bytes + the StrategyAlert JSON; the
  StrategyRetryPollerService reuses the stored chart (never re-rendering), re-runs
  the AI analyst, re-sends, and degrades after the max attempts. The live
  DynamoDB round-trip of the item is covered by
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
    And the enqueued strategy pending alert carries the rendered chart bytes
    And the strategy dispatch counts queued 1

  Scenario: The poller reuses the stored chart bytes and does not re-render
    Given a strategy pending alert is queued with retry_count 0 and a stored chart due now
    When the strategy retry poller runs
    Then the strategy chart is not re-rendered
    And a full strategy email is sent to "alice@example.com"
    And the strategy pending alert is deleted

  Scenario: Chart-stage failures degrade after the max attempts
    Given a strategy pending alert is queued with retry_count 2 and no stored chart due now
    When the strategy retry poller runs
    Then a degraded strategy email is sent without a chart
    And the strategy pending alert is deleted

  Scenario: A transient failure under the cap bumps the retry count
    Given a strategy pending alert is queued with retry_count 0 and a stored chart due now
    And the AI analyst will fail the next 1 calls
    When the strategy retry poller runs
    Then no strategy email is sent
    And the strategy pending alert retry_count is 1

  Scenario: All recipients rejected on the final attempt drops the poison item
    Given a strategy pending alert is queued with retry_count 2 and a stored chart due now
    And the email sender will reject recipient "alice@example.com"
    When the strategy retry poller runs
    Then the strategy pending alert is deleted
