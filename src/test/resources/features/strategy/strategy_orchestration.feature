Feature: SI-3 — Strategy orchestration in monitoring-main

  Executable counterpart of CLAUDE.md Block 16 (SI-3 orchestration): for an
  instrument monitored by a strategy, a run evaluates the strategy on the latest
  bar and routes the StrategyAlert to the dedicated strategy dispatch — the
  legacy fixed patterns stay suppressed.

  Background:
    Given the registry is empty
    And current UTC time is "2026-05-07T22:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS"
    And an instrument "AAPL" on "NASDAQ" already exists

  Scenario: A strategy instrument dispatches a strategy alert and suppresses legacy
    Given the recipients for "AAPL" are "alice@example.com"
    And the color_change pattern is enabled with min_streak_length 1
    And the instrument is monitored by a strategy whose scenario "entry" has conditions:
      | close is above 100 |
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-01T00:00:00Z | 90   | 92   | 88  | 90    |
      | 2026-05-02T00:00:00Z | 90   | 94   | 88  | 92    |
      | 2026-05-03T00:00:00Z | 92   | 96   | 90  | 94    |
      | 2026-05-04T00:00:00Z | 94   | 98   | 92  | 96    |
      | 2026-05-05T00:00:00Z | 96   | 100  | 94  | 98    |
      | 2026-05-06T00:00:00Z | 98   | 107  | 96  | 105   |
    When I run monitoring-main
    Then the main summary reports 1 alerts sent
    And a strategy email is sent to "alice@example.com"

  # SI-3 read-consistency (CLAUDE.md Block 16): the persisted findLastN read is
  # eventually consistent and runs immediately after the ingest write — when it
  # lags, the run must still evaluate the just-ingested latest bar (it merges the
  # inserted bars into the series), never the stale tail. A miss here is a
  # permanently skipped alert: the next run evaluates the next bar.
  Scenario: Strategy evaluation sees the freshly ingested bar even when the persisted read lags
    Given the recipients for "AAPL" are "alice@example.com"
    And the instrument is monitored by a strategy whose scenario "entry" has conditions:
      | close is above 100 |
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-01T00:00:00Z | 90   | 92   | 88  | 90    |
      | 2026-05-02T00:00:00Z | 90   | 94   | 88  | 92    |
      | 2026-05-03T00:00:00Z | 92   | 96   | 90  | 94    |
      | 2026-05-04T00:00:00Z | 94   | 98   | 92  | 96    |
      | 2026-05-05T00:00:00Z | 96   | 100  | 94  | 98    |
      | 2026-05-06T00:00:00Z | 98   | 107  | 96  | 105   |
    And the persisted OHLC read lags behind the just-ingested bar
    When I run monitoring-main
    Then the main summary reports 1 alerts sent
    And the dispatched strategy alert carries bar time "2026-05-06T00:00:00Z"

  # SI-3 failure surfacing (CLAUDE.md Block 16): strategy dispatch persistence
  # failures fail the run exactly like the legacy path — never swallowed as an
  # instrument failure while the invocation "succeeds".
  Scenario: A strategy audit-write failure after a delivered email fails the run like legacy
    Given the recipients for "AAPL" are "alice@example.com"
    And audit logging is enabled
    And the instrument is monitored by a strategy whose scenario "entry" has conditions:
      | close is above 100 |
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-05T00:00:00Z | 96   | 100  | 94  | 98    |
      | 2026-05-06T00:00:00Z | 98   | 107  | 96  | 105   |
    And the audit write will fail
    When I run monitoring-main expecting failure
    Then a strategy email is sent to "alice@example.com"
    And the run fails with an unhandled error

  Scenario: A strategy retry-enqueue failure fails the run like legacy
    Given the recipients for "AAPL" are "alice@example.com"
    And the instrument is monitored by a strategy whose scenario "entry" has conditions:
      | close is above 100 |
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-05T00:00:00Z | 96   | 100  | 94  | 98    |
      | 2026-05-06T00:00:00Z | 98   | 107  | 96  | 105   |
    And the strategy chart renderer will fail the next 1 calls
    And the strategy pending-alert write will fail
    When I run monitoring-main expecting failure
    Then the run fails with an unhandled error
