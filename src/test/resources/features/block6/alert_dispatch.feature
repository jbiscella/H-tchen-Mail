Feature: Block 6 — alert dispatch and retry orchestration

  Executable counterparts of CLAUDE.md §9. The chart renderer, AI analyst,
  email sender and audit repository are fakes so the dispatch + retry
  semantics can be exercised independently of any production adapter.

  Background:
    Given the registry is empty
    And current UTC time is "2026-05-07T22:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS"
    And an instrument "AAPL" on "NASDAQ" already exists
    And the recipients for "AAPL" are "alice@example.com,bot@example.com"

  Scenario: Single event, all enrichments succeed
    Given a staged pattern event for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "color_change/bullish_reversal"
    When I dispatch the staged events
    Then the dispatch summary has sent=1, queued=0, skipped=0
    And the email sender recorded 1 full send for 2 recipients
    And no alerts are pending

  Scenario: Chart rendering fails on first attempt → enqueue retry
    Given a staged pattern event for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "color_change/bullish_reversal"
    And the chart renderer will fail the next 1 calls
    When I dispatch the staged events
    Then the dispatch summary has sent=0, queued=1, skipped=0
    And no email is sent
    And 1 alert is pending with retry_count 0 and last_error code "CHART_RENDER_FAILED"

  Scenario: AI fails on first attempt → enqueue retry
    Given a staged pattern event for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "color_change/bullish_reversal"
    And the AI analyst will fail the next 1 calls
    When I dispatch the staged events
    Then the dispatch summary has sent=0, queued=1, skipped=0
    And 1 alert is pending with retry_count 0 and last_error code "LLM_ERROR"

  Scenario: Recipients empty → skip with warning, no enqueue
    Given the recipients for "AAPL" are ""
    And a staged pattern event for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "doji/doji"
    When I dispatch the staged events
    Then the dispatch summary has sent=0, queued=0, skipped=1
    And no alerts are pending
    And no email is sent

  Scenario: Per-recipient SES failure isolation
    Given a staged pattern event for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "doji/doji"
    And the email sender will reject recipient "bot@example.com"
    When I dispatch the staged events
    Then the dispatch summary has sent=1, queued=0, skipped=0
    And the email sender recorded 1 full send for 2 recipients
    And the last send delivered exactly 1 recipient

  Scenario: Retry poller skips items not yet due
    Given a pending alert exists for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "color_change/bullish_reversal" and retry_at "2026-05-07T23:00:00Z" and retry_count 0
    When I run the retry poller
    Then the poll result has processed=0
    And 1 alert is pending

  Scenario: Retry poller succeeds on retry → deletes pending and sends full
    Given a pending alert exists for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "color_change/bullish_reversal" and retry_at "2026-05-07T21:00:00Z" and retry_count 0
    When I run the retry poller
    Then the poll result has processed=1, sent_full=1, sent_degraded=0, requeued=0
    And no alerts are pending
    And the email sender recorded 1 full send for 2 recipients

  Scenario: Retry poller failure under attempt cap bumps retry_count
    Given a pending alert exists for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "color_change/bullish_reversal" and retry_at "2026-05-07T21:00:00Z" and retry_count 0
    And the chart renderer will fail the next 1 calls
    And the AI analyst will fail the next 1 calls
    When I run the retry poller
    Then the poll result has processed=1, sent_full=0, sent_degraded=0, requeued=1
    And 1 alert is pending with retry_count 1

  Scenario: Retry poller on third attempt with chart still failing → degraded chart email
    Given a pending alert exists for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "color_change/bullish_reversal" and retry_at "2026-05-07T21:00:00Z" and retry_count 2
    And the chart renderer will fail the next 1 calls
    When I run the retry poller
    Then the poll result has processed=1, sent_full=0, sent_degraded=1, requeued=0
    And no alerts are pending
    And the email sender recorded 1 degraded send with enrichment "degraded_chart"

  Scenario: Retry poller on third attempt with AI still failing → degraded AI email
    Given a pending alert exists for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "color_change/bullish_reversal" and retry_at "2026-05-07T21:00:00Z" and retry_count 2
    And the AI analyst will fail the next 1 calls
    When I run the retry poller
    Then the poll result has processed=1, sent_full=0, sent_degraded=1, requeued=0
    And the email sender recorded 1 degraded send with enrichment "degraded_ai"

  Scenario: Retry poller on third attempt with both still failing → fully degraded email
    Given a pending alert exists for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "color_change/bullish_reversal" and retry_at "2026-05-07T21:00:00Z" and retry_count 2
    And the chart renderer will fail the next 1 calls
    And the AI analyst will fail the next 1 calls
    When I run the retry poller
    Then the poll result has processed=1, sent_full=0, sent_degraded=1, requeued=0
    And the email sender recorded 1 degraded send with enrichment "degraded_both"
    And no alerts are pending

  Scenario: Audit enabled records ALERT after successful dispatch
    Given audit logging is enabled
    And a staged pattern event for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "color_change/bullish_reversal"
    When I dispatch the staged events
    Then the dispatch summary has sent=1, queued=0, skipped=0
    And the audit repository has 1 entry with enrichment "full" and 2 recipients

  Scenario: Idempotent enqueue — double dispatch of same event keeps the original retry state
    Given a staged pattern event for "AAPL" on "1d" at "2026-05-06T00:00:00Z" with pattern "color_change/bullish_reversal"
    And the chart renderer will fail the next 2 calls
    When I dispatch the staged events
    And I dispatch the staged events
    Then 1 alert is pending with retry_count 0
