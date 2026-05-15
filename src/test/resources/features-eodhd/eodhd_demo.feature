@network
Feature: EODHD provider — demo key smoke

  Real network test against the public EODHD "demo" API token. Confirms the
  production EodhdMarketDataProvider can hit the real endpoint, that URLs
  are built correctly, and that response shape matches what the parser
  expects. Skipped automatically when EODHD is unreachable from the test
  runner (offline dev, network-restricted CI).

  Demo-supported symbols per EODHD docs: AAPL.US, TSLA.US, MCD.US, VTI.US,
  AMZN.US — used here as the full equity exemplar set. Other demo-capable
  symbols (crypto / forex / indices) are out of scope for this monitoring
  service.

  Scenario Outline: Fetch real EOD daily bars for a demo-supported symbol
    When I fetch the last 30 days of "<symbol>" at timeframe "1d"
    Then the response contains at least one bar
    And every bar has open, high, low, close strictly greater than 0
    And every bar respects the OHLC invariants
    And every bar has source "eodhd"
    And the bars are sorted ascending by bar_time

    Examples:
      | symbol  |
      | AAPL.US |
      | TSLA.US |
      | MCD.US  |
      | VTI.US  |
      | AMZN.US |

  Scenario: Weekly timeframe still parses for AAPL.US
    When I fetch the last 90 days of "AAPL.US" at timeframe "1w"
    Then the response contains at least one bar
    And every bar has source "eodhd"

  Scenario: Unknown ticker yields a domain TickerNotFoundException
    When I attempt to fetch the last 30 days of "ZZZZZZ.US" at timeframe "1d"
    Then a TickerNotFoundException is raised
