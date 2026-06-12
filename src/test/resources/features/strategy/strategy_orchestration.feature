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
