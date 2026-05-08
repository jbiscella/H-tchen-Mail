Feature: Block 5 — HA pattern detection

  Executable counterparts of CLAUDE.md §8. Bars are pre-built in DB; the
  detector is pure (no DB writes) and is exercised through the
  PatternDetectionService.

  Background:
    Given the registry is empty
    And current UTC time is "2026-05-07T22:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS"
    And an instrument "AAPL" on "NASDAQ" already exists

  Scenario: No new bars yields no events
    When I detect patterns on "1d" with no new HA bars
    Then 0 pattern events are emitted

  Scenario: All patterns disabled yields no events
    Given the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-06T00:00:00Z | 100     | 110     | 95     | 108      |
    When I detect patterns on "1d" using the bars at "2026-05-06T00:00:00Z"
    Then 0 pattern events are emitted

  Scenario: Bullish reversal detected
    Given the color_change pattern is enabled with min_streak_length 3
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-01T00:00:00Z | 110     | 110     | 95     | 100      |
      | 2026-05-02T00:00:00Z | 100     | 100     | 90     | 92       |
      | 2026-05-03T00:00:00Z | 92      | 92      | 80     | 85       |
      | 2026-05-04T00:00:00Z | 85      | 100     | 84     | 98       |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then a pattern event is emitted with pattern "color_change" and subtype "bullish_reversal"
    And the event params_used contains "min_streak_length" with value 3

  Scenario: Bearish reversal detected
    Given the color_change pattern is enabled with min_streak_length 3
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-01T00:00:00Z | 90      | 105     | 90     | 100      |
      | 2026-05-02T00:00:00Z | 100     | 115     | 100    | 110      |
      | 2026-05-03T00:00:00Z | 110     | 125     | 110    | 120      |
      | 2026-05-04T00:00:00Z | 120     | 121     | 105    | 108      |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then a pattern event is emitted with pattern "color_change" and subtype "bearish_reversal"

  Scenario: Streak too short, no event
    Given the color_change pattern is enabled with min_streak_length 3
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-02T00:00:00Z | 100     | 100     | 90     | 92       |
      | 2026-05-03T00:00:00Z | 92      | 92      | 80     | 85       |
      | 2026-05-04T00:00:00Z | 85      | 100     | 84     | 98       |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then 0 pattern events are emitted

  Scenario: NEUTRAL bar in the streak breaks it
    Given the color_change pattern is enabled with min_streak_length 3
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-01T00:00:00Z | 110     | 110     | 95     | 100      |
      | 2026-05-02T00:00:00Z | 100     | 100     | 100    | 100      |
      | 2026-05-03T00:00:00Z | 92      | 92      | 80     | 85       |
      | 2026-05-04T00:00:00Z | 85      | 100     | 84     | 98       |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then 0 pattern events are emitted

  Scenario: Current bar NEUTRAL, no color_change
    Given the color_change pattern is enabled with min_streak_length 2
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-02T00:00:00Z | 100     | 100     | 90     | 92       |
      | 2026-05-03T00:00:00Z | 92      | 92      | 80     | 85       |
      | 2026-05-04T00:00:00Z | 85      | 90      | 80     | 85       |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then 0 pattern events are emitted

  Scenario: Insufficient history yields no event
    Given the color_change pattern is enabled with min_streak_length 3
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-04T00:00:00Z | 85      | 100     | 84     | 98       |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then 0 pattern events are emitted

  Scenario: Bullish strong candle detected
    Given the strong_candle pattern is enabled with wick_tolerance 0.001 and min_body_ratio 0.5
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-04T00:00:00Z | 100     | 112     | 100    | 110      |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then a pattern event is emitted with pattern "strong_candle" and subtype "bullish_strong"

  Scenario: Bearish strong candle detected
    Given the strong_candle pattern is enabled with wick_tolerance 0.001 and min_body_ratio 0.5
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-04T00:00:00Z | 100     | 100     | 88     | 92       |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then a pattern event is emitted with pattern "strong_candle" and subtype "bearish_strong"

  Scenario: Body too small, no strong candle
    Given the strong_candle pattern is enabled with wick_tolerance 0.001 and min_body_ratio 0.5
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-04T00:00:00Z | 100     | 110     | 100    | 102      |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then no pattern event with pattern "strong_candle" is emitted

  Scenario: Wick too large, no strong candle
    Given the strong_candle pattern is enabled with wick_tolerance 0.001 and min_body_ratio 0.5
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-04T00:00:00Z | 100     | 112     | 95     | 110      |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then no pattern event with pattern "strong_candle" is emitted

  Scenario: Range zero yields no strong_candle and no doji
    Given the strong_candle pattern is enabled with wick_tolerance 0.001 and min_body_ratio 0.5
    And the doji pattern is enabled with max_body_ratio 0.1
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-04T00:00:00Z | 100     | 100     | 100    | 100      |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then 0 pattern events are emitted

  Scenario: Doji detected
    Given the doji pattern is enabled with max_body_ratio 0.1
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-04T00:00:00Z | 100     | 104     | 96     | 100.5    |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then a pattern event is emitted with pattern "doji" and subtype "doji"

  Scenario: Body too large, no doji
    Given the doji pattern is enabled with max_body_ratio 0.1
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-04T00:00:00Z | 100     | 110     | 95     | 107      |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then no pattern event with pattern "doji" is emitted

  Scenario: Multiple patterns on the same bar each emit their own event
    Given the color_change pattern is enabled with min_streak_length 3
    And the strong_candle pattern is enabled with wick_tolerance 0.001 and min_body_ratio 0.5
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-01T00:00:00Z | 110     | 110     | 95     | 100      |
      | 2026-05-02T00:00:00Z | 100     | 100     | 90     | 92       |
      | 2026-05-03T00:00:00Z | 92      | 92      | 80     | 85       |
      | 2026-05-04T00:00:00Z | 85      | 100     | 85     | 100      |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then 2 pattern events are emitted

  Scenario: Detection is deterministic
    Given the color_change pattern is enabled with min_streak_length 3
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-01T00:00:00Z | 110     | 110     | 95     | 100      |
      | 2026-05-02T00:00:00Z | 100     | 100     | 90     | 92       |
      | 2026-05-03T00:00:00Z | 92      | 92      | 80     | 85       |
      | 2026-05-04T00:00:00Z | 85      | 100     | 84     | 98       |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    And I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then both detections produced the same events

  Scenario: Multiple new bars in a single run
    Given the color_change pattern is enabled with min_streak_length 3
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-01T00:00:00Z | 110     | 110     | 95     | 100      |
      | 2026-05-02T00:00:00Z | 100     | 100     | 90     | 92       |
      | 2026-05-03T00:00:00Z | 92      | 92      | 80     | 85       |
      | 2026-05-04T00:00:00Z | 85      | 100     | 84     | 98       |
      | 2026-05-05T00:00:00Z | 98      | 100     | 90     | 92       |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z,2026-05-05T00:00:00Z"
    Then exactly 1 pattern event is emitted
    And the only event has pattern "color_change" and subtype "bullish_reversal"

  Scenario: Event includes denormalized ticker, exchange, and bar_snapshot
    Given the doji pattern is enabled with max_body_ratio 0.1
    And the following "1d" OHLC bars exist for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-04T00:00:00Z | 100  | 104  | 96  | 100   |
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-04T00:00:00Z | 100     | 104     | 96     | 100.5    |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then the event has ticker "AAPL" and exchange "NASDAQ"
    And the event bar_snapshot has open 100, high 104, low 96, close 100
    And the event bar_snapshot has ha_open 100, ha_high 104, ha_low 96, ha_close 100.5
