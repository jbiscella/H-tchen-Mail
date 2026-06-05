Feature: SI-3c.1 — Strategy alert chart rendering

  The dedicated StrategyChartRenderer builds the StrategyChartSpec (SI-2) and
  renders it to PNG bytes via the headless heerwisch driver, so the strategy
  alert email can embed the chart (CLAUDE.md §9 Component 1b, SI-3c.1).

  Background:
    Given an HA lookback window of 60 bars

  Scenario: The strategy alert chart renders to a PNG the email can embed
    Given a strategy whose single scenario has conditions:
      | rsi(14) crosses below 30    |
      | close crosses above sma(50) |
    And a strategy alert with a "long_entry" line on the latest bar
    When the strategy alert chart is rendered
    Then a PNG chart image of 900x500 is produced
