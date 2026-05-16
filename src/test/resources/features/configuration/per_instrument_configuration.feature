Feature: Block 2 — Per-instrument configuration domain operations

  Executable counterparts of CLAUDE.md §5. Defaults are written by the
  Block 1 register flow; this feature exercises the get / update_* use
  cases.

  Background:
    Given the registry is empty
    And current UTC time is "2026-05-07T22:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS"
    And an instrument "AAPL" on "NASDAQ" already exists

  Scenario: Default config is created with the instrument
    When I get the config for the instrument
    Then the config has storage policy "ROLLING_WINDOW" and rolling window size 200
    And the config has tracked timeframes "1d"
    And the config has all patterns disabled
    And the config has no recipients
    And the config has chart enabled and AI analysis enabled

  Scenario: Get config for non-existent instrument
    When I get the config for instrument id "missing-id"
    Then the operation fails with code "INSTRUMENT_NOT_FOUND"

  Scenario: Switch to FULL_HISTORY removes the rolling window size
    When I update the storage policy to "FULL_HISTORY"
    Then the config has storage policy "FULL_HISTORY" and no rolling window size

  Scenario: Switch to ROLLING_WINDOW with a new size
    When I update the storage policy to "ROLLING_WINDOW" with window size 500
    Then the config has storage policy "ROLLING_WINDOW" and rolling window size 500

  Scenario: Switch to ROLLING_WINDOW without size fails
    When I update the storage policy to "ROLLING_WINDOW" without window size
    Then the operation fails with code "MISSING_WINDOW_SIZE"

  Scenario: Switch to ROLLING_WINDOW with invalid size fails
    When I update the storage policy to "ROLLING_WINDOW" with window size 0
    Then the operation fails with code "INVALID_WINDOW_SIZE"

  Scenario: Switch to SNAPSHOT_ONLY removes the rolling window size
    When I update the storage policy to "SNAPSHOT_ONLY"
    Then the config has storage policy "SNAPSHOT_ONLY" and no rolling window size

  Scenario: Unknown storage policy is rejected
    When I update the storage policy to "FOOBAR"
    Then the operation fails with code "INVALID_STORAGE_POLICY"

  Scenario: Set both supported timeframes
    When I update tracked timeframes to "1d,1w"
    Then the config has tracked timeframes "1d,1w"

  Scenario: Reject unsupported timeframe
    When I update tracked timeframes to "1d,3h"
    Then the operation fails with code "UNSUPPORTED_TIMEFRAME"

  Scenario: Reject empty timeframes
    When I update tracked timeframes to ""
    Then the operation fails with code "EMPTY_TIMEFRAMES"

  Scenario: Duplicate timeframes are normalized
    When I update tracked timeframes to "1d,1d"
    Then the config has tracked timeframes "1d"

  Scenario: Enable color_change with custom streak length
    When I update pattern "color_change" with:
      | enabled            | true |
      | min_streak_length  | 5    |
    Then the config has color_change enabled with min_streak_length 5

  Scenario: Disable a pattern preserves its parameters
    When I update pattern "color_change" with:
      | enabled            | true |
      | min_streak_length  | 5    |
    And I update pattern "color_change" with:
      | enabled  | false |
    Then the config has color_change disabled with min_streak_length 5

  Scenario: Reject unknown pattern name
    When I update pattern "foo" with:
      | enabled | true |
    Then the operation fails with code "INVALID_PATTERN_CONFIG"

  Scenario: Reject min_streak_length below 1
    When I update pattern "color_change" with:
      | enabled            | true |
      | min_streak_length  | 0    |
    Then the operation fails with code "INVALID_PATTERN_CONFIG"

  Scenario: Reject doji.max_body_ratio out of (0,1]
    When I update pattern "doji" with:
      | enabled         | true |
      | max_body_ratio  | 1.5  |
    Then the operation fails with code "INVALID_PATTERN_CONFIG"

  Scenario: Reject strong_candle.min_body_ratio out of (0,1]
    When I update pattern "strong_candle" with:
      | enabled         | true |
      | min_body_ratio  | 0    |
      | wick_tolerance  | 0.001 |
    Then the operation fails with code "INVALID_PATTERN_CONFIG"

  Scenario: Reject strong_candle.wick_tolerance below zero
    When I update pattern "strong_candle" with:
      | enabled         | true |
      | wick_tolerance  | -0.001 |
      | min_body_ratio  | 0.5    |
    Then the operation fails with code "INVALID_PATTERN_CONFIG"

  Scenario: Set valid recipients
    When I update recipients to "me@example.com,bot@example.com"
    Then the config has recipients "me@example.com,bot@example.com"

  Scenario: Reject invalid email format
    When I update recipients to "not-an-email"
    Then the operation fails with code "INVALID_RECIPIENT"

  Scenario: Reject empty recipients list
    When I update recipients to ""
    Then the operation fails with code "EMPTY_RECIPIENTS"

  Scenario: Duplicate recipients are normalized
    When I update recipients to "a@x.com,a@x.com"
    Then the config has recipients "a@x.com"

  # CLAUDE.md §5: optimistic locking via a version attribute is deferred, so
  # two updates to the same field simply last-write-win — the second persists.
  Scenario: Concurrent config updates are last-write-wins
    When I update recipients to "first@x.com"
    And I update recipients to "second@x.com"
    Then the config has recipients "second@x.com"
