Feature: SI-2 — Strategy alert chart spec

  The chart for a strategy alert is built from the strategy's derived overlays
  (SI-1) placed in their panes, plus a direction marker on the matched bar taken
  from the alert line's role (display-only; see CLAUDE.md §9 Component 1b).

  Executable counterpart of CLAUDE.md §9 Component 1b (SI-2), exercised through
  the pure StrategyChartSpec builder (no DB, no PNG rendering).

  Background:
    Given an HA lookback window of 60 bars

  Scenario: The strategy chart spec carries the derived overlays in their panes
    Given a strategy whose single scenario has conditions:
      | rsi(14) crosses below 30    |
      | close crosses above sma(50) |
    And a strategy alert with a "long_entry" line on the latest bar
    When I build the strategy chart spec
    Then the spec places "RSI" with period 14 in a sub-pane
    And the spec places "SMA" with period 50 in the "MAIN" pane

  # ha-track 0.57: candles are Heikin-Ashi (display) drawn from the raw OHLC
  # series, so the real-close indicators above are faithful to the rule.
  Scenario: The strategy chart spec renders Heikin-Ashi candle bodies
    Given a strategy whose single scenario has conditions:
      | rsi(14) crosses below 30 |
    And a strategy alert with a "long_entry" line on the latest bar
    When I build the strategy chart spec
    Then the chart spec uses the "HEIKIN_ASHI" candle style

  Scenario: A long_entry line places an up-triangle entry marker on the trigger bar
    Given a strategy whose single scenario has conditions:
      | rsi(14) crosses below 30 |
    And a strategy alert with a "long_entry" line on the latest bar
    When I build the strategy chart spec
    Then the spec has an entry marker on the trigger bar with direction "LONG_ENTRY" and glyph "UP_TRIANGLE"

  Scenario: A long_exit line places a down-triangle exit marker on the trigger bar
    Given a strategy whose single scenario has conditions:
      | macd_bearish_cross() |
    And a strategy alert with a "long_exit" line on the latest bar
    When I build the strategy chart spec
    Then the spec has an entry marker on the trigger bar with direction "LONG_EXIT" and glyph "DOWN_TRIANGLE"

  Scenario: An unrecognized role falls back to a neutral bar highlight
    Given a strategy whose single scenario has conditions:
      | rsi(14) crosses below 30 |
    And a strategy alert with a "watch" line on the latest bar
    When I build the strategy chart spec
    Then the spec has a neutral bar highlight on the trigger bar and no entry marker
