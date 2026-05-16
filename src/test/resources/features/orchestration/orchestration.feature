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

  # The detector emits one event per matching bar; on a long bootstrap chain
  # that can mean many stale events. LatestBarEventFilter (CLAUDE.md §10)
  # keeps only the events on the chronologically last bar and logs
  # main_events_suppressed for the rest. This 12-bar chain produces three
  # Heikin-Ashi colour flips → three color_change events on three bars; only
  # the latest survives, two are suppressed.
  Scenario: Only the latest detected bar fires an alert; older bars are suppressed
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the recipients for "AAPL" are "alice@example.com"
    And the color_change pattern is enabled with min_streak_length 1
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-04-21T00:00:00Z | 300  | 302  | 278 | 280   |
      | 2026-04-22T00:00:00Z | 280  | 282  | 258 | 260   |
      | 2026-04-23T00:00:00Z | 260  | 262  | 238 | 240   |
      | 2026-04-24T00:00:00Z | 240  | 262  | 238 | 260   |
      | 2026-04-25T00:00:00Z | 260  | 282  | 258 | 280   |
      | 2026-04-26T00:00:00Z | 280  | 302  | 278 | 300   |
      | 2026-04-27T00:00:00Z | 300  | 302  | 278 | 280   |
      | 2026-04-28T00:00:00Z | 280  | 282  | 258 | 260   |
      | 2026-04-29T00:00:00Z | 260  | 262  | 238 | 240   |
      | 2026-04-30T00:00:00Z | 240  | 262  | 238 | 260   |
      | 2026-05-01T00:00:00Z | 260  | 282  | 258 | 280   |
      | 2026-05-02T00:00:00Z | 280  | 302  | 278 | 300   |
    When I run monitoring-main
    Then the main summary has processed=1 and succeeded=1 and failed=0
    And the main summary reports 1 events detected
    And the main summary reports 1 alerts sent
    And the logs contain a "main_events_suppressed" line with "kept=1 suppressed=2"

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

  # ---- force_email: manual smoke escape hatch -----------------------------
  #
  # When the payload includes force_email=true, every tracked timeframe
  # without a real pattern this run gets a synthetic FORCED PatternEvent
  # built from the latest persisted HA + OHLC bar. Chart + AI + email all
  # run end-to-end. Useful for ops verification ("can the pipeline send
  # mail right now?") without waiting for a real pattern to fire.

  Scenario: force_email synthesises one event per tracked timeframe when no real pattern fires
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the recipients for "AAPL" are "alice@example.com"
    And a previously stored "1d" bar for "AAPL" at "2026-05-06T00:00:00Z"
    And HA has previously been computed on "1d" for "AAPL" from the full OHLC chain
    And the provider has no bars for any symbol
    When I run monitoring-main with force_email true
    Then the main summary has processed=1 and succeeded=1 and failed=0
    And the main summary reports 1 events detected
    And the main summary reports 1 alerts sent

  Scenario: force_email does NOT duplicate when a real pattern already fired
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
    When I run monitoring-main with force_email true
    Then the main summary has processed=1 and succeeded=1 and failed=0

  Scenario: force_email skips silently when no HA bar exists yet
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the recipients for "AAPL" are "alice@example.com"
    And the provider has no bars for any symbol
    When I run monitoring-main with force_email true
    Then the main summary has processed=1 and succeeded=1 and failed=0
    And the main summary reports 0 events detected
    And the main summary reports 0 alerts sent

  Scenario: force_email targeted at one instrument doesn't fire for others
    Given the following instruments exist:
      | ticker | exchange | status |
      | AAPL   | NASDAQ   | active |
      | MSFT   | NASDAQ   | active |
    And the recipients for "AAPL" are "alice@example.com"
    And the recipients for "MSFT" are "alice@example.com"
    And a previously stored "1d" bar for "AAPL" at "2026-05-06T00:00:00Z"
    And HA has previously been computed on "1d" for "AAPL" from the full OHLC chain
    And a previously stored "1d" bar for "MSFT" at "2026-05-06T00:00:00Z"
    And HA has previously been computed on "1d" for "MSFT" from the full OHLC chain
    And the provider has no bars for any symbol
    When I run monitoring-main for instruments "AAPL" with force_email true
    Then the main summary has processed=1 and succeeded=1 and failed=0
    And the main summary reports 1 alerts sent

  Scenario: force_email respects the no-recipients skip
    Given an instrument "AAPL" on "NASDAQ" already exists
    And a previously stored "1d" bar for "AAPL" at "2026-05-06T00:00:00Z"
    And HA has previously been computed on "1d" for "AAPL" from the full OHLC chain
    And the provider has no bars for any symbol
    When I run monitoring-main with force_email true
    Then the main summary has processed=1 and succeeded=1 and failed=0
    And the main summary reports 1 events detected
    And the main summary reports 0 alerts sent

  Scenario: Default payload still does not send when no pattern fires
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the recipients for "AAPL" are "alice@example.com"
    And a previously stored "1d" bar for "AAPL" at "2026-05-06T00:00:00Z"
    And HA has previously been computed on "1d" for "AAPL" from the full OHLC chain
    And the provider has no bars for any symbol
    When I run monitoring-main
    Then the main summary has processed=1 and succeeded=1 and failed=0
    And the main summary reports 0 events detected
    And the main summary reports 0 alerts sent
