Feature: Block 3 — news aggregation across providers

  Executable counterpart of CLAUDE.md §6 "News & fundamentals provider
  composition". The NewsAggregator fans fetchNewsHeadlines out across every
  enabled NewsProvider in parallel, drops a failing provider without failing
  the call, then merges, de-duplicates, sorts newest-first, and caps the
  result.

  Since Block 18 the aggregator also enforces the recency window centrally, so
  every fixture below is dated inside it: the steps run on a fixed clock of
  2026-06-12T15:00:00Z and the 1d look-back is 7 days, which opens the window on
  2026-06-05. These scenarios assert merge order, dedup and capping — none of
  them is about dates — so their fixtures simply have to sit inside the window
  to keep testing what they were written to test.

  Scenario: Both providers are merged, newest first
    Given a news provider "marketaux" returning:
      | title | published_at         | url           |
      | older | 2026-06-10T00:00:00Z | https://a/old |
    And a news provider "yahoo-rss" returning:
      | title | published_at         | url           |
      | newer | 2026-06-12T00:00:00Z | https://b/new |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 10
    Then the aggregated headlines are "newer,older"

  Scenario: A failing provider is dropped, not fatal
    Given a news provider "ok" returning:
      | title    | published_at         | url          |
      | survives | 2026-06-12T00:00:00Z | https://a/ok |
    And a news provider "bad" that fails
    And the enabled news providers are "ok,bad"
    When I aggregate news with max 10
    Then the aggregated headlines are "survives"

  Scenario: A disabled provider is not queried
    Given a news provider "marketaux" returning:
      | title | published_at         | url         |
      | kept  | 2026-06-12T00:00:00Z | https://a/k |
    And a news provider "yahoo-rss" returning:
      | title   | published_at         | url         |
      | dropped | 2026-06-13T00:00:00Z | https://b/d |
    And the enabled news providers are "marketaux"
    When I aggregate news with max 10
    Then the aggregated headlines are "kept"

  Scenario: The max cap is applied after the merge
    Given a news provider "marketaux" returning:
      | title | published_at         | url          |
      | h1    | 2026-06-12T00:00:00Z | https://a/1  |
      | h2    | 2026-06-11T00:00:00Z | https://a/2  |
    And a news provider "yahoo-rss" returning:
      | title | published_at         | url          |
      | h3    | 2026-06-10T00:00:00Z | https://b/3  |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 2
    Then the aggregated result has 2 headlines

  Scenario: Exact URL duplicates collapse to one
    Given a news provider "marketaux" returning:
      | title                   | published_at         | url           |
      | Title one               | 2026-06-12T10:00:00Z | https://x/dup |
    And a news provider "yahoo-rss" returning:
      | title                   | published_at         | url           |
      | Different headline text | 2026-06-12T09:00:00Z | https://x/dup |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 10
    Then the aggregated result has 1 headlines

  Scenario: The same story within one hour collapses
    Given a news provider "marketaux" returning:
      | title                        | published_at         | url         |
      | Richemont posts record sales | 2026-06-12T10:00:00Z | https://a/x |
    And a news provider "yahoo-rss" returning:
      | title                          | published_at         | url         |
      | RICHEMONT  posts   record sales | 2026-06-12T10:45:00Z | https://b/y |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 10
    Then the aggregated result has 1 headlines

  Scenario: The same title more than one hour apart is kept
    Given a news provider "marketaux" returning:
      | title                        | published_at         | url         |
      | Richemont posts record sales | 2026-06-12T12:00:00Z | https://a/x |
    And a news provider "yahoo-rss" returning:
      | title                        | published_at         | url         |
      | Richemont posts record sales | 2026-06-12T10:00:00Z | https://b/y |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 10
    Then the aggregated result has 2 headlines

  Scenario: Empty URLs do not collapse unrelated headlines
    Given a news provider "marketaux" returning:
      | title                  | published_at         | url |
      | First story            | 2026-06-12T12:00:00Z |     |
    And a news provider "yahoo-rss" returning:
      | title                  | published_at         | url |
      | Second unrelated story | 2026-06-12T11:00:00Z |     |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 10
    Then the aggregated result has 2 headlines

  Scenario: No enabled providers yields an empty list
    Given a news provider "marketaux" returning:
      | title | published_at         | url         |
      | kept  | 2026-06-12T00:00:00Z | https://a/k |
    And the enabled news providers are ""
    When I aggregate news with max 10
    Then the aggregated result is empty

  # --- Block 17 — EODHD news provider (CLAUDE.md Block 17) -------------------
  # These scenarios drive the real EodhdNewsProvider through a scripted HTTP
  # exchange (no network), composed into the same NewsAggregator as above.

  Scenario: EODHD headlines are merged with the other providers
    Given the enabled news providers are "marketaux,yahoo-rss,eodhd"
    And the EODHD provider returns a headline published at "2026-06-12T10:00:00Z" for "NVDA" on "NASDAQ"
    And the Marketaux provider returns a headline published at "2026-06-12T08:00:00Z" for "NVDA" on "NASDAQ"
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then 2 headlines are returned
    And the first headline is the one published at "2026-06-12T10:00:00Z"

  Scenario: A long EODHD article body is truncated into the summary
    Given the enabled news providers are "eodhd"
    And the summary limit is 600 characters
    And the EODHD provider returns a headline whose content is 4000 characters long
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then the returned summary is at most 600 characters
    And the summary ends at a word boundary with an ellipsis

  Scenario: A short EODHD article body is carried verbatim (control)
    Given the enabled news providers are "eodhd"
    And the summary limit is 600 characters
    And the EODHD provider returns a headline whose content is 200 characters long
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then the returned summary is the content verbatim, without ellipsis

  Scenario: An EODHD item without content yields an empty summary
    Given the enabled news providers are "eodhd"
    And the EODHD provider returns a headline with no content
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then 1 headline is returned with an empty summary

  Scenario: The source is derived from the link host
    Given the enabled news providers are "eodhd"
    And the EODHD provider returns a headline linking to "https://finance.yahoo.com/markets/article-x"
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then the headline source is "finance.yahoo.com"

  Scenario: A malformed link yields an empty source, not a failure
    Given the enabled news providers are "eodhd"
    And the EODHD provider returns a headline whose link is not a valid URL
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then 1 headline is returned with an empty source

  Scenario: The recency window passed to EODHD matches the Marketaux derivation
    Given the enabled news providers are "marketaux,eodhd"
    And the pattern timeframe is "1d"
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then the EODHD provider was queried with the same recency window as the Marketaux provider

  Scenario: EODHD disabled keeps the legacy pair untouched (control)
    Given the enabled news providers are "marketaux,yahoo-rss"
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then the EODHD provider is never queried

  Scenario: A failing EODHD provider is dropped, not fatal
    Given the enabled news providers are "marketaux,yahoo-rss,eodhd"
    And the EODHD provider fails
    And the Marketaux provider returns 1 headline for "NVDA" on "NASDAQ"
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then 1 headline is returned

  Scenario: A content without spaces within the limit is hard-cut
    Given the enabled news providers are "eodhd"
    And the summary limit is 600 characters
    And the EODHD provider returns a headline whose content is a single 4000-character word
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then the returned summary is exactly 600 characters plus an ellipsis

  Scenario: A non-UTC publication offset is normalized to Instant UTC
    Given the enabled news providers are "eodhd"
    And the EODHD provider returns a headline dated "2026-06-12T12:00:00+02:00"
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then the headline publishedAt is "2026-06-12T10:00:00Z"

  Scenario: The EODHD news query uses the configured exchange suffix
    Given the enabled news providers are "eodhd"
    And an instrument "ENI" on "MIL"
    When I fetch news headlines for "ENI" on "MIL" with max 5
    Then the EODHD provider was queried with symbol "ENI.MI"

  # ------------------------------------------------------------------
  # Block 18 — candidate pool: scope, over-fetch, filter, then cap
  #
  # The Marketaux query contract (must_have_entities present,
  # filter_entities absent) is verified in MarketauxNewsProviderTest
  # instead: that provider builds its HttpClient internally and has no
  # injectable exchange seam here, and its buildUri is asserted directly.
  # ------------------------------------------------------------------

  Scenario: The candidate pool is over-fetched, filtered, then capped to the max
    Given the candidate pool size is 30
    And the maximum entities per item is 6
    And the enabled news providers are "eodhd"
    And the EODHD provider holds 30 items within the recency window where 6 carry 2 symbols and the rest carry 20
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then the EODHD provider was queried for 30 items
    And 5 headlines are returned
    And every returned headline is one of the low-cardinality items

  Scenario: A multi-company digest is dropped on entity cardinality
    Given the maximum entities per item is 6
    And the enabled news providers are "eodhd"
    And the EODHD provider returns an item titled "Q2 2026 earnings call summaries" carrying 14 symbols
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then 0 headlines are returned

  Scenario: A single-company item is kept regardless of which company it is
    Given the maximum entities per item is 6
    And the enabled news providers are "eodhd"
    And the EODHD provider returns an item titled "Amadeus IT Group H1 2026 results" carrying 1 symbol
    When I fetch news headlines for "AMS" on "BME" with max 5
    Then 1 headline is returned

  Scenario: A provider supplying no entity list is not penalized
    Given the maximum entities per item is 6
    And the enabled news providers are "eodhd"
    And the EODHD provider returns an item with no symbol list published 2 days before now
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then 1 headline is returned

  Scenario: An item outside the recency window is dropped even from a provider with no date filter
    Given the pattern timeframe is "1d"
    And a news provider "yahoo-rss" returning:
      | title  | published_at         | url            |
      | stale  | 2026-04-13T00:00:00Z | https://y/old  |
      | fresh  | 2026-06-10T00:00:00Z | https://y/new  |
    And the enabled news providers are "yahoo-rss"
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then 1 headline is returned
    And the aggregated headlines are "fresh"

  Scenario: A promotional screener title shape is dropped
    Given the enabled news providers are "yahoo-rss"
    And a news provider "yahoo-rss" returning:
      | title                                        | published_at         | url          |
      | SIG vs CFRUY: Which Is the Better Value Stock? | 2026-06-11T00:00:00Z | https://y/p |
    When I fetch news headlines for "CFR" on "SWX" with max 5
    Then 0 headlines are returned

  Scenario: The promotional filter is instrument-independent
    Given the enabled news providers are "yahoo-rss"
    And a news provider "yahoo-rss" returning:
      | title                                      | published_at         | url          |
      | AAA vs BBB: Which Is the Better Value Stock? | 2026-06-11T00:00:00Z | https://y/q |
    When I fetch news headlines for "GAW" on "LSE" with max 5
    Then 0 headlines are returned

  Scenario: Filtering everything out yields zero headlines, not a failure
    Given the pattern timeframe is "1d"
    And a news provider "yahoo-rss" returning:
      | title | published_at         | url           |
      | stale | 2026-01-02T00:00:00Z | https://y/old |
    And the enabled news providers are "yahoo-rss"
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then 0 headlines are returned

  Scenario: A future-dated headline is excluded from the recency window
    Given the pattern timeframe is "1d"
    And a news provider "yahoo-rss" returning:
      | title     | published_at         | url          |
      | scheduled | 2026-06-20T00:00:00Z | https://y/fu |
      | genuine   | 2026-06-11T00:00:00Z | https://y/ok |
    And the enabled news providers are "yahoo-rss"
    When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
    Then 1 headline is returned
    And the aggregated headlines are "genuine"
