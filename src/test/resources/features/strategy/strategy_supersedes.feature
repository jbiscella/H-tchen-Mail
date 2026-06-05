Feature: SI-3b — A strategy supersedes the legacy fixed patterns

  Executable counterpart of CLAUDE.md Block 15 "Strategy supersedes the three
  fixed patterns": an instrument monitored by an imported strategy no longer
  emits the legacy color_change / strong_candle / doji alerts — only the
  strategy's scenarios can raise alerts.

  Background:
    Given the registry is empty
    And current UTC time is "2026-05-07T22:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS"
    And an instrument "AAPL" on "NASDAQ" already exists

  Scenario: Without a strategy the legacy color_change still fires (control)
    Given the color_change pattern is enabled with min_streak_length 3
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-01T00:00:00Z | 110     | 110     | 95     | 100      |
      | 2026-05-02T00:00:00Z | 100     | 100     | 90     | 92       |
      | 2026-05-03T00:00:00Z | 92      | 92      | 80     | 85       |
      | 2026-05-04T00:00:00Z | 85      | 100     | 84     | 98       |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then a pattern event is emitted with pattern "color_change" and subtype "bullish_reversal"

  Scenario: With a strategy the legacy fixed patterns are suppressed
    Given the color_change pattern is enabled with min_streak_length 3
    And the instrument is monitored by a strategy whose scenario "entry" has conditions:
      | rsi(14) crosses below 30 |
    And the following "1d" HA bars are seeded for "AAPL":
      | bar_time             | ha_open | ha_high | ha_low | ha_close |
      | 2026-05-01T00:00:00Z | 110     | 110     | 95     | 100      |
      | 2026-05-02T00:00:00Z | 100     | 100     | 90     | 92       |
      | 2026-05-03T00:00:00Z | 92      | 92      | 80     | 85       |
      | 2026-05-04T00:00:00Z | 85      | 100     | 84     | 98       |
    When I detect patterns on "1d" using the bars at "2026-05-04T00:00:00Z"
    Then 0 pattern events are emitted
