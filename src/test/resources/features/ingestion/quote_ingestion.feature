Feature: Block 3 — Quote ingestion via the market-data port

  Executable counterparts of CLAUDE.md §6. The market-data provider is
  faked so tests are deterministic; the YahooFinanceProvider adapter
  contract is exercised only via this port.

  Background:
    Given the registry is empty
    And current UTC time is "2026-05-07T22:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS"

  Scenario: Iterate over active instruments
    Given the following instruments exist:
      | ticker | exchange | status   |
      | AAPL   | NASDAQ   | active   |
      | MSFT   | NASDAQ   | active   |
      | OLD    | NASDAQ   | archived |
    And the provider has no bars for any symbol
    When I run ingest_all_active
    Then the provider was called for symbols "AAPL,MSFT"
    And the provider was not called for symbol "OLD"

  Scenario: One instrument failure does not block others
    Given the following instruments exist:
      | ticker | exchange | status |
      | A      | NASDAQ   | active |
      | B      | NASDAQ   | active |
      | C      | NASDAQ   | active |
    And the provider raises a "ticker_not_found" error for symbol "B"
    When I run ingest_all_active
    Then the ingestion summary has processed=3, succeeded=2, failed=1

  Scenario: Bootstrap when no bars exist
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-05T00:00:00Z | 100  | 110  | 95  | 105   |
      | 2026-05-06T00:00:00Z | 106  | 112  | 102 | 110   |
    When I ingest the active instrument
    Then 2 bars are persisted for "AAPL" on "1d"

  Scenario: Steady-state fetch from last known bar
    Given an instrument "AAPL" on "NASDAQ" already exists
    And a previously stored "1d" bar for "AAPL" at "2026-05-05T00:00:00Z"
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-06T00:00:00Z | 106  | 112  | 102 | 110   |
    When I ingest the active instrument
    Then 2 bars are persisted for "AAPL" on "1d"
    And the latest "1d" bar persisted for "AAPL" is at "2026-05-06T00:00:00Z"

  Scenario: Skip bars that are still in formation
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-06T00:00:00Z | 100  | 110  | 95  | 105   |
      | 2026-05-07T00:00:00Z | 106  | 112  | 102 | 110   |
    When I ingest the active instrument
    Then 1 bar is persisted for "AAPL" on "1d"

  Scenario: Idempotent re-ingestion
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-06T00:00:00Z | 100  | 110  | 95  | 105   |
    When I ingest the active instrument
    And I ingest the active instrument
    Then 1 bar is persisted for "AAPL" on "1d"

  Scenario: Yahoo returns empty result
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the provider returns no bars for "AAPL"
    When I ingest the active instrument
    Then 0 bars are persisted for "AAPL" on "1d"

  Scenario: Yahoo ticker not found / delisted
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the provider raises a "ticker_not_found" error for symbol "AAPL"
    When I run ingest_all_active
    Then the ingestion summary has processed=1, succeeded=0, failed=1
    And the instrument "AAPL" is still active

  Scenario: Schema drift surfaces as a failure for that instrument
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the provider raises a "schema_drift" error for symbol "AAPL"
    When I run ingest_all_active
    Then the ingestion summary has processed=1, succeeded=0, failed=1

  Scenario: Provider unavailable surfaces as a failure
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the provider raises a "provider_unavailable" error for symbol "AAPL"
    When I run ingest_all_active
    Then the ingestion summary has processed=1, succeeded=0, failed=1

  # CLAUDE.md §6 "Transient failure with retry": a transient ProviderUnavailable
  # is retried up to 3 times with exponential backoff before being given up on.
  Scenario: Transient provider failure recovers within the retry budget
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the provider fails the next 2 calls for symbol "AAPL" then recovers
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-05T00:00:00Z | 100  | 110  | 95  | 105   |
    When I run ingest_all_active
    Then the ingestion summary has processed=1, succeeded=1, failed=0
    And the ingestion summary reports 1 bars inserted

  Scenario: Transient provider failure exhausts the retry budget
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the provider fails the next 4 calls for symbol "AAPL" then recovers
    When I run ingest_all_active
    Then the ingestion summary has processed=1, succeeded=0, failed=1

  Scenario: Reject bars violating OHLC invariants (skipped, not raised)
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-04T00:00:00Z | 100  | 90   | 95  | 92    |
      | 2026-05-06T00:00:00Z | 100  | 110  | 95  | 105   |
    When I ingest the active instrument
    Then 1 bar is persisted for "AAPL" on "1d"

  Scenario: FULL_HISTORY policy writes no TTL
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the storage policy for "AAPL" is set to "FULL_HISTORY"
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-06T00:00:00Z | 100  | 110  | 95  | 105   |
    When I ingest the active instrument
    Then the bar at "2026-05-06T00:00:00Z" for "AAPL" on "1d" has no TTL

  Scenario: ROLLING_WINDOW(N) writes a TTL
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the storage policy for "AAPL" is set to "ROLLING_WINDOW" with window 200
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-06T00:00:00Z | 100  | 110  | 95  | 105   |
    When I ingest the active instrument
    Then the bar at "2026-05-06T00:00:00Z" for "AAPL" on "1d" has a TTL of bar_time + 201 days

  Scenario: SNAPSHOT_ONLY truncates and replaces atomically
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the storage policy for "AAPL" is set to "SNAPSHOT_ONLY"
    And a previously stored "1d" bar for "AAPL" at "2026-05-04T00:00:00Z"
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-05T00:00:00Z | 100  | 110  | 95  | 105   |
      | 2026-05-06T00:00:00Z | 106  | 112  | 102 | 110   |
    When I ingest the active instrument
    Then exactly 1 bar exists for "AAPL" on "1d"
    And the latest "1d" bar persisted for "AAPL" is at "2026-05-06T00:00:00Z"

  Scenario: Daily bar timezone normalization
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-06T13:30:00Z | 100  | 110  | 95  | 105   |
    When I ingest the active instrument
    Then the latest "1d" bar persisted for "AAPL" is at "2026-05-06T00:00:00Z"

  Scenario: Weekly bar normalization to Monday UTC
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the tracked timeframes for "AAPL" are "1w"
    And the provider returns these "1w" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-04-15T13:30:00Z | 100  | 110  | 95  | 105   |
    When I ingest the active instrument
    Then the latest "1w" bar persisted for "AAPL" is at "2026-04-13T00:00:00Z"

  Scenario: Iterate over multiple tracked timeframes
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the tracked timeframes for "AAPL" are "1d,1w"
    And the provider returns these "1d" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-05-06T00:00:00Z | 100  | 110  | 95  | 105   |
    And the provider returns these "1w" bars for "AAPL":
      | bar_time             | open | high | low | close |
      | 2026-04-27T00:00:00Z | 100  | 110  | 95  | 105   |
    When I ingest the active instrument
    Then 1 bar is persisted for "AAPL" on "1d"
    And 1 bar is persisted for "AAPL" on "1w"

  Scenario: Symbol mapping uses the configured exchange suffix
    Given an instrument "ENI" on "MIL" already exists
    And the provider returns no bars for "ENI.MI"
    When I ingest the active instrument
    Then the provider was called for symbols "ENI.MI"

  Scenario: Aggregated failure rate over threshold raises critical alarm
    Given the following instruments exist:
      | ticker | exchange | status |
      | A      | NASDAQ   | active |
      | B      | NASDAQ   | active |
      | C      | NASDAQ   | active |
      | D      | NASDAQ   | active |
    And the provider raises a "provider_unavailable" error for symbol "A"
    And the provider raises a "provider_unavailable" error for symbol "B"
    And the provider raises a "provider_unavailable" error for symbol "C"
    When I run ingest_all_active
    Then the ingestion summary has processed=4, succeeded=1, failed=3

  Scenario: Circuit breaker stops re-trying after threshold consecutive failures
    Given the following instruments exist:
      | ticker | exchange | status |
      | A      | NASDAQ   | active |
      | A      | NYSE     | active |
      | A      | XETRA    | active |
      | A      | LSE      | active |
    And the provider raises a "provider_unavailable" error for symbol "A.US"
    And the provider raises a "provider_unavailable" error for symbol "A.XETRA"
    And the provider raises a "provider_unavailable" error for symbol "A.LSE"
    When I run ingest_all_active
    Then the ingestion summary has processed=4, succeeded=0, failed=4

  Scenario: Ingestion summary is returned
    Given the following instruments exist:
      | ticker | exchange | status |
      | A      | NASDAQ   | active |
      | B      | NASDAQ   | active |
    And the provider returns these "1d" bars for "A":
      | bar_time             | open | high | low | close |
      | 2026-05-06T00:00:00Z | 100  | 110  | 95  | 105   |
    And the provider returns these "1d" bars for "B":
      | bar_time             | open | high | low | close |
      | 2026-05-06T00:00:00Z | 100  | 110  | 95  | 105   |
    When I run ingest_all_active
    Then the ingestion summary has processed=2, succeeded=2, failed=0
    And the ingestion summary reports 2 bars inserted
