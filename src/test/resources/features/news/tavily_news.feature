Feature: Block 19 — web-search news query resolution

  The query a web-search news provider sends, which is the operator-facing half of
  Block 19. The bare ticker is a defect rather than a style choice: measured against
  Tavily, "AMS.MC" returned AWS single-account landing-zone documentation, Amsterdam
  flight listings and Perplexity.

  The HTTP-shaped behaviour — topic=news, include_domains, published_date parsing and
  the quota codes — is pinned by AAA tests in TavilyNewsProviderTest at the pure seams
  (requestBody / parseNews), following the Block 18 Part A precedent: wiring Cucumber
  around a stubbed transport would test the stub, not the adapter.

  Background:
    Given the registry is empty
    And current UTC time is "2026-08-12T10:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS,BME,SWX"

  Scenario: The derived query uses the instrument name, not the bare ticker
    Given an instrument "AMS" on "BME" named "Amadeus IT Group SA"
    When the news query for "AMS" on "BME" is resolved
    Then the news query is "Amadeus IT Group SA AMS shares"

  Scenario: A configured news_query overrides the derived one
    Given an instrument "AMS" on "BME" named "Amadeus IT Group SA"
    And the news_query for "AMS" on "BME" is "Amadeus IT Group AMS.MC shares"
    When the news query for "AMS" on "BME" is resolved
    Then the news query is "Amadeus IT Group AMS.MC shares"

  Scenario: An instrument with no name degrades to the ticker
    Given an instrument "GAW" on "LSE" with no name
    When the news query for "GAW" on "LSE" is resolved
    Then the news query is "GAW"

  Scenario: Clearing the override returns to the derived query
    Given an instrument "AMS" on "BME" named "Amadeus IT Group SA"
    And the news_query for "AMS" on "BME" is "something bespoke"
    And the news_query for "AMS" on "BME" is cleared
    When the news query for "AMS" on "BME" is resolved
    Then the news query is "Amadeus IT Group SA AMS shares"

  Scenario: Changing an unrelated config setting keeps the override
    Given an instrument "AMS" on "BME" named "Amadeus IT Group SA"
    And the news_query for "AMS" on "BME" is "Amadeus IT Group AMS.MC shares"
    And the recipients for "AMS" are "alice@example.com"
    When the news query for "AMS" on "BME" is resolved
    Then the news query is "Amadeus IT Group AMS.MC shares"
