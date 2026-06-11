Feature: SI-1 — Strategy-driven chart overlays

  The alert chart for a strategy-monitored instrument must explain the rule
  visually with ONLY the relevant diagrams: it shows exactly the indicators the
  strategy's scenario conditions reference, and nothing else. Heikin-Ashi
  primitives add no overlay (they are already the candles); an indicator
  referenced by several scenarios is drawn once.

  Executable counterpart of CLAUDE.md §9 Component 1 (strategy-aware), exercised
  through the pure StrategyChartIndicators derivation.

  Scenario: Only the referenced oscillator is derived; HA primitives add nothing
    Given a strategy whose single scenario has conditions:
      | rsi(14) crosses below 30 |
      | ha_bullish_reversal(3)   |
    When I derive the chart indicators
    Then the derived indicators are exactly:
      | type | period |
      | RSI  | 14     |

  Scenario: An indicator referenced by several scenarios is derived once
    Given a strategy whose scenarios have these conditions:
      | scenario | condition            |
      | entry    | macd_bullish_cross() |
      | exit     | macd_bearish_cross() |
    When I derive the chart indicators
    Then the derived indicators are exactly:
      | type | fast | slow | signal |
      | MACD | 12   | 26   | 9      |

  Scenario: Moving averages become main-pane overlays
    Given a strategy whose single scenario has conditions:
      | close crosses above sma(50)  |
      | close crosses below ema(200) |
    When I derive the chart indicators
    Then the derived indicators are exactly:
      | type | period |
      | SMA  | 50     |
      | EMA  | 200    |
    And every derived indicator is placed in the "MAIN" pane

  Scenario: A pure Heikin-Ashi strategy adds no overlays
    Given a strategy whose single scenario has conditions:
      | ha_doji()           |
      | ha_strong_bullish() |
    When I derive the chart indicators
    Then no chart indicators are derived

  # The overlays must read the SAME raw price the rule engine evaluates on
  # (DslConditionEvaluator uses raw OHLC, never Heikin-Ashi). Candles stay HA
  # via CandleStyle.HEIKIN_ASHI (ha-track 0.57); only the indicator math is raw.

  Scenario: Oscillator and MA overlays read the raw close, not the HA close
    Given a strategy whose single scenario has conditions:
      | rsi(14) crosses below 30    |
      | close crosses above sma(50) |
    When I derive the chart indicators
    Then every derived indicator reads the raw "CLOSE" price source

  Scenario: HHV/LLV channels read raw HIGH and LOW, not the HA extremes
    Given a strategy whose single scenario has conditions:
      | close crosses above highest_high(20) |
      | close crosses below lowest_low(20)   |
    When I derive the chart indicators
    Then the "RollingMax" overlay reads the raw "HIGH" price source
    And the "RollingMin" overlay reads the raw "LOW" price source
