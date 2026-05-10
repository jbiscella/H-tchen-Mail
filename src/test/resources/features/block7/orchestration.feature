Feature: Block 7 — monitoring-main pipeline orchestration

  Executable counterparts of CLAUDE.md §10. Drives the full per-instrument
  pipeline (ingest → HA → detect → dispatch) over the in-memory adapters.

  Background:
    Given the registry is empty
    And current UTC time is "2026-05-07T22:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS"

  Scenario: No active instruments → fast exit
    When I run monitoring-main
    Then the main summary has processed=0 and succeeded=0 and failed=0

  Scenario: Scheduled run with quiet market produces a clean summary
    Given the following instruments exist:
      | ticker | exchange | status |
      | AAPL   | NASDAQ   | active |
      | MSFT   | NASDAQ   | active |
      | GOOG   | NASDAQ   | active |
    And the provider has no bars for any symbol
    When I run monitoring-main
    Then the main summary has processed=3 and succeeded=3 and failed=0
    And the main summary reports 0 bars inserted

  Scenario: Scheduled run with bars triggers downstream pipeline end-to-end
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the recipients for "AAPL" are "alice@example.com"
    And the color_change pattern is enabled with min_streak_length 3
    And a previously stored "1d" bar for "AAPL" at "2026-05-01T00:00:00Z"
    And a previously stored "1d" bar for "AAPL" at "2026-05-02T00:00:00Z"
    And a previously stored "1d" bar for "AAPL" at "2026-05-03T00:00:00Z"
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-04T00:00:00Z | 100  | 110  | 95  | 105   |
      | 2026-05-05T00:00:00Z | 106  | 112  | 102 | 110   |
      | 2026-05-06T00:00:00Z | 110  | 116  | 108 | 114   |
    When I run monitoring-main
    Then the main summary has processed=1 and succeeded=1 and failed=0
    And the main summary reports 3 bars inserted

  Scenario: One instrument failure does not block others
    Given the following instruments exist:
      | ticker | exchange | status |
      | A      | NASDAQ   | active |
      | B      | NASDAQ   | active |
      | C      | NASDAQ   | active |
    And the provider raises a "provider_unavailable" error for symbol "B"
    When I run monitoring-main
    Then the main summary has processed=3 and succeeded=2 and failed=1

  Scenario: Manual one-shot run for a single instrument
    Given the following instruments exist:
      | ticker | exchange | status |
      | AAPL   | NASDAQ   | active |
      | MSFT   | NASDAQ   | active |
      | GOOG   | NASDAQ   | active |
    And the provider has no bars for any symbol
    When I run monitoring-main for instruments "MSFT"
    Then the main summary has processed=1 and succeeded=1 and failed=0

  Scenario: Manual run with an unknown instrument id is silently skipped
    When I run monitoring-main for instruments "missing-id"
    Then the main summary has processed=0 and succeeded=0 and failed=0

  Scenario: Manual run for an archived instrument is silently skipped
    Given the following instruments exist:
      | ticker | exchange | status   |
      | AAPL   | NASDAQ   | active   |
      | MSFT   | NASDAQ   | archived |
    When I run monitoring-main for instruments by ticker "MSFT"
    Then the main summary has processed=0 and succeeded=0 and failed=0

  Scenario: Soft timeout stops queueing new instruments
    Given the following instruments exist:
      | ticker | exchange | status |
      | A      | NASDAQ   | active |
      | B      | NASDAQ   | active |
      | C      | NASDAQ   | active |
    And the soft timeout is 0 minutes
    When I run monitoring-main
    Then the main summary reports the soft timeout was hit
    And the main summary has processed=0
