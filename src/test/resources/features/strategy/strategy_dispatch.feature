Feature: SI-3c.2 — Strategy alert dispatch (first attempt)

  Executable counterpart of CLAUDE.md §9 Component 1c: a StrategyAlert is
  dispatched by the dedicated StrategyAlertDispatchService — render chart → AI →
  email — preserving the matched lines. First-attempt happy path + no-recipients
  skip (retry is SI-3c.3).

  Background:
    Given the registry is empty
    And current UTC time is "2026-05-07T22:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS"
    And an instrument "AAPL" on "NASDAQ" already exists

  Scenario: A strategy alert is dispatched with chart, AI and email
    Given the recipients for "AAPL" are "alice@example.com"
    And a strategy alert for "AAPL" with a "long_entry" scenario "oversold-entry"
    When the strategy alert is dispatched
    Then the strategy chart is rendered
    And the AI analyst runs for the strategy alert
    And a strategy email is sent to "alice@example.com"
    And the strategy dispatch counts sent 1

  Scenario: A strategy alert with no recipients is skipped
    Given the recipients for "AAPL" are ""
    And a strategy alert for "AAPL" with a "long_entry" scenario "oversold-entry"
    When the strategy alert is dispatched
    Then no strategy email is sent
    And the strategy dispatch counts skipped 1
