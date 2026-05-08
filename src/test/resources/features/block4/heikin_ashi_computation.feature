Feature: Block 4 — Heikin-Ashi computation

  Executable counterparts of CLAUDE.md §7. Uses the in-memory OHLC and HA
  repositories so the calculation is exercised independently of any backing
  store.

  Background:
    Given the registry is empty
    And current UTC time is "2026-05-07T22:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS"
    And an instrument "AAPL" on "NASDAQ" already exists

  Scenario: First-time computation seeds from OHLC values
    Given the following "1d" OHLC bars exist for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-01T00:00:00Z | 100  | 110  | 95  | 105   |
      | 2026-05-02T00:00:00Z | 106  | 112  | 102 | 110   |
      | 2026-05-03T00:00:00Z | 110  | 115  | 108 | 113   |
    When I compute HA for the active instrument on "1d" with those OHLC bars
    Then 3 HA bars exist for "AAPL" on "1d"
    And the HA bar at "2026-05-01T00:00:00Z" has ha_open 102.5
    And the returned HA chain has 3 bars

  Scenario: Incremental computation uses prior HA as seed
    Given a previously stored "1d" HA bar for "AAPL" at "2026-05-04T00:00:00Z" with ha_open 100 and ha_close 110
    And the following "1d" OHLC bars exist for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-05T00:00:00Z | 110  | 120  | 108 | 118   |
    When I compute HA for the active instrument on "1d" with the OHLC at "2026-05-05T00:00:00Z"
    Then the HA bar at "2026-05-05T00:00:00Z" has ha_open 105
    And 2 HA bars exist for "AAPL" on "1d"

  Scenario: No new OHLC bars is a no-op
    When I compute HA for the active instrument on "1d" with no OHLC bars
    Then the returned HA chain has 0 bars
    And 0 HA bars exist for "AAPL" on "1d"

  Scenario: Idempotent recomputation produces identical results
    Given the following "1d" OHLC bars exist for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-01T00:00:00Z | 100  | 110  | 95  | 105   |
    When I compute HA for the active instrument on "1d" with the OHLC at "2026-05-01T00:00:00Z"
    And I compute HA for the active instrument on "1d" with the OHLC at "2026-05-01T00:00:00Z"
    Then 1 HA bar exists for "AAPL" on "1d"

  Scenario: OHLC retroactively changed triggers chain recompute from that point
    Given the following "1d" OHLC bars exist for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-01T00:00:00Z | 100  | 110  | 95  | 105   |
      | 2026-05-02T00:00:00Z | 106  | 112  | 102 | 110   |
      | 2026-05-03T00:00:00Z | 110  | 115  | 108 | 113   |
    And HA has previously been computed on "1d" from the full OHLC chain
    When the OHLC at "2026-05-02T00:00:00Z" is overwritten with values 106, 120, 100, 119
    And I compute HA for the active instrument on "1d" with the OHLC at "2026-05-02T00:00:00Z"
    Then the HA bar at "2026-05-02T00:00:00Z" has ha_close 111.25
    And 3 HA bars exist for "AAPL" on "1d"

  Scenario: HA invariants always hold
    Given the following "1d" OHLC bars exist for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-01T00:00:00Z | 100  | 110  | 95  | 105   |
    When I compute HA for the active instrument on "1d" with the full OHLC chain
    Then for every persisted HA bar, ha_high is at least ha_low
    And for every persisted HA bar, ha_high is at least ha_open and ha_close
    And for every persisted HA bar, ha_low is at most ha_open and ha_close

  Scenario: FULL_HISTORY policy writes no TTL on HA bars
    Given the storage policy for "AAPL" is set to "FULL_HISTORY"
    And the following "1d" OHLC bars exist for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-01T00:00:00Z | 100  | 110  | 95  | 105   |
    When I compute HA for the active instrument on "1d" with the full OHLC chain
    Then the HA bar at "2026-05-01T00:00:00Z" for "AAPL" on "1d" has no TTL

  Scenario: ROLLING_WINDOW(N) writes TTL on HA mirroring OHLC
    Given the storage policy for "AAPL" is set to "ROLLING_WINDOW" with window 200
    And the following "1d" OHLC bars exist for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-01T00:00:00Z | 100  | 110  | 95  | 105   |
    When I compute HA for the active instrument on "1d" with the full OHLC chain
    Then the HA bar at "2026-05-01T00:00:00Z" for "AAPL" on "1d" has a TTL of bar_time + 201 days

  Scenario: SNAPSHOT_ONLY truncates and replaces HA atomically
    Given the storage policy for "AAPL" is set to "SNAPSHOT_ONLY"
    And the following "1d" OHLC bars exist for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-01T00:00:00Z | 100  | 110  | 95  | 105   |
      | 2026-05-02T00:00:00Z | 106  | 112  | 102 | 110   |
    When I compute HA for the active instrument on "1d" with the full OHLC chain
    Then exactly 1 HA bar exists for "AAPL" on "1d"

  Scenario: Bulk recompute drops existing HA and rebuilds from scratch
    Given the following "1d" OHLC bars exist for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-01T00:00:00Z | 100  | 110  | 95  | 105   |
      | 2026-05-02T00:00:00Z | 106  | 112  | 102 | 110   |
    And HA has previously been computed on "1d" from the full OHLC chain
    When I bulk-recompute HA for the active instrument on "1d"
    Then 2 HA bars exist for "AAPL" on "1d"
    And the bulk recompute reports 2 OHLC and 2 HA bars

  Scenario: Bulk recompute with no OHLC bars yields zero
    When I bulk-recompute HA for the active instrument on "1d"
    Then 0 HA bars exist for "AAPL" on "1d"
    And the bulk recompute reports 0 OHLC and 0 HA bars
