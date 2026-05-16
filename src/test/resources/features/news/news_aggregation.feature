Feature: Block 3 — news aggregation across providers

  Executable counterpart of CLAUDE.md §6 "News & fundamentals provider
  composition". The NewsAggregator fans fetchNewsHeadlines out across every
  enabled NewsProvider in parallel, drops a failing provider without failing
  the call, then merges, de-duplicates, sorts newest-first, and caps the
  result.

  Scenario: Both providers are merged, newest first
    Given a news provider "marketaux" returning:
      | title | published_at         | url           |
      | older | 2026-05-10T00:00:00Z | https://a/old |
    And a news provider "yahoo-rss" returning:
      | title | published_at         | url           |
      | newer | 2026-05-12T00:00:00Z | https://b/new |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 10
    Then the aggregated headlines are "newer,older"

  Scenario: A failing provider is dropped, not fatal
    Given a news provider "ok" returning:
      | title    | published_at         | url          |
      | survives | 2026-05-12T00:00:00Z | https://a/ok |
    And a news provider "bad" that fails
    And the enabled news providers are "ok,bad"
    When I aggregate news with max 10
    Then the aggregated headlines are "survives"

  Scenario: A disabled provider is not queried
    Given a news provider "marketaux" returning:
      | title | published_at         | url         |
      | kept  | 2026-05-12T00:00:00Z | https://a/k |
    And a news provider "yahoo-rss" returning:
      | title   | published_at         | url         |
      | dropped | 2026-05-13T00:00:00Z | https://b/d |
    And the enabled news providers are "marketaux"
    When I aggregate news with max 10
    Then the aggregated headlines are "kept"

  Scenario: The max cap is applied after the merge
    Given a news provider "marketaux" returning:
      | title | published_at         | url          |
      | h1    | 2026-05-12T00:00:00Z | https://a/1  |
      | h2    | 2026-05-11T00:00:00Z | https://a/2  |
    And a news provider "yahoo-rss" returning:
      | title | published_at         | url          |
      | h3    | 2026-05-10T00:00:00Z | https://b/3  |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 2
    Then the aggregated result has 2 headlines

  Scenario: Exact URL duplicates collapse to one
    Given a news provider "marketaux" returning:
      | title                   | published_at         | url           |
      | Title one               | 2026-05-12T10:00:00Z | https://x/dup |
    And a news provider "yahoo-rss" returning:
      | title                   | published_at         | url           |
      | Different headline text | 2026-05-12T09:00:00Z | https://x/dup |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 10
    Then the aggregated result has 1 headlines

  Scenario: The same story within one hour collapses
    Given a news provider "marketaux" returning:
      | title                        | published_at         | url         |
      | Richemont posts record sales | 2026-05-12T10:00:00Z | https://a/x |
    And a news provider "yahoo-rss" returning:
      | title                          | published_at         | url         |
      | RICHEMONT  posts   record sales | 2026-05-12T10:45:00Z | https://b/y |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 10
    Then the aggregated result has 1 headlines

  Scenario: The same title more than one hour apart is kept
    Given a news provider "marketaux" returning:
      | title                        | published_at         | url         |
      | Richemont posts record sales | 2026-05-12T12:00:00Z | https://a/x |
    And a news provider "yahoo-rss" returning:
      | title                        | published_at         | url         |
      | Richemont posts record sales | 2026-05-12T10:00:00Z | https://b/y |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 10
    Then the aggregated result has 2 headlines

  Scenario: Empty URLs do not collapse unrelated headlines
    Given a news provider "marketaux" returning:
      | title                  | published_at         | url |
      | First story            | 2026-05-12T12:00:00Z |     |
    And a news provider "yahoo-rss" returning:
      | title                  | published_at         | url |
      | Second unrelated story | 2026-05-12T11:00:00Z |     |
    And the enabled news providers are "marketaux,yahoo-rss"
    When I aggregate news with max 10
    Then the aggregated result has 2 headlines

  Scenario: No enabled providers yields an empty list
    Given a news provider "marketaux" returning:
      | title | published_at         | url         |
      | kept  | 2026-05-12T00:00:00Z | https://a/k |
    And the enabled news providers are ""
    When I aggregate news with max 10
    Then the aggregated result is empty
