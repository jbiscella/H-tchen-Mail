Feature: Block 1 — Instrument Registry domain operations

  These scenarios are executable counterparts of CLAUDE.md §4. They run
  against an in-memory repository fake that mirrors the atomicity
  guarantees of the DynamoDB adapter.

  Background:
    Given the registry is empty
    And current UTC time is "2026-05-07T22:00:00Z"
    And the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS"

  Scenario: Register a valid equity
    When I register ticker "aapl" on exchange "nasdaq" with name "Apple Inc." and currency "USD"
    Then the registration succeeds
    And the result has ticker "AAPL", exchange "NASDAQ", and status "active"
    And the result has created_at and updated_at equal to the current UTC time
    And a UNIQUE_LOCK exists for ticker "AAPL" on exchange "NASDAQ"
    And a CONFIG default has been provisioned for the new instrument

  Scenario: Register on the Swiss and Spanish exchanges
    # SWX and BME are in the production supported set (application.yml) but
    # absent from the default background — register on both to guard the
    # exchanges this project actually tracks (e.g. CFR.SW, SAN.MC).
    Given the supported exchanges are "NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS,SWX,BME"
    When I register ticker "CFR" on exchange "SWX"
    Then the registration succeeds
    And the result has ticker "CFR", exchange "SWX", and status "active"
    When I register ticker "SAN" on exchange "BME"
    Then the registration succeeds
    And the result has ticker "SAN", exchange "BME", and status "active"

  Scenario: Reject duplicate (ticker, exchange) pair
    Given an instrument "AAPL" on "NASDAQ" already exists
    When I register ticker "AAPL" on exchange "NASDAQ"
    Then the operation fails with code "DUPLICATE_INSTRUMENT"

  Scenario Outline: Reject empty or whitespace ticker before any persistence
    When I register ticker "<ticker>" on exchange "NASDAQ"
    Then the operation fails with code "INVALID_TICKER"
    And the registry remains empty

    Examples:
      | ticker  |
      |         |
      | AA PL   |
      |    \t   |

  Scenario: Reject unsupported exchange
    When I register ticker "AAPL" on exchange "FOOBAR"
    Then the operation fails with code "UNSUPPORTED_EXCHANGE"

  Scenario: Get instrument by id
    Given an instrument "MSFT" on "NASDAQ" already exists
    When I look up the instrument by its id
    Then the result has ticker "MSFT", exchange "NASDAQ", and status "active"

  Scenario: Get non-existent instrument
    When I look up the instrument with id "missing-id"
    Then the operation fails with code "INSTRUMENT_NOT_FOUND"

  Scenario: List active instruments by default
    Given the following instruments exist:
      | ticker | exchange | status   |
      | AAPL   | NASDAQ   | active   |
      | MSFT   | NASDAQ   | active   |
      | GOOG   | NASDAQ   | active   |
      | YHOO   | NASDAQ   | archived |
    When I list instruments with status "active"
    Then 3 instruments are returned

  Scenario: List archived instruments
    Given the following instruments exist:
      | ticker | exchange | status   |
      | AAPL   | NASDAQ   | active   |
      | YHOO   | NASDAQ   | archived |
    When I list instruments with status "archived"
    Then 1 instrument is returned

  Scenario: Pagination walks the full active set
    Given 30 active instruments exist
    When I list instruments with status "active" and page size 10
    Then 10 instruments are returned and a next cursor is present
    When I list the next page with page size 10
    Then 10 instruments are returned and a next cursor is present
    When I list the next page with page size 10
    Then 10 instruments are returned and no next cursor is present

  Scenario: Update mutable metadata
    Given an instrument "AAPL" on "NASDAQ" already exists
    When I update its metadata with name "Apple Inc." and currency "USD"
    Then the result has name "Apple Inc." and currency "USD"
    And ticker, exchange, status, and created_at are unchanged

  Scenario Outline: Reject update of immutable fields
    When I attempt to update field "<field>"
    Then the operation fails with code "IMMUTABLE_FIELD"

    Examples:
      | field      |
      | id         |
      | ticker     |
      | exchange   |
      | createdAt  |
      | created_at |

  Scenario: Archive an active instrument preserves lock and history
    Given an instrument "AAPL" on "NASDAQ" already exists
    When I archive the instrument
    Then the result has status "archived"
    And a UNIQUE_LOCK exists for ticker "AAPL" on exchange "NASDAQ"

  Scenario: Restore an archived instrument
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the instrument has been archived
    When I restore the instrument
    Then the result has status "active"

  Scenario: Archive non-existent instrument
    When I archive the instrument with id "missing-id"
    Then the operation fails with code "INSTRUMENT_NOT_FOUND"

  Scenario: Hard delete removes all traces
    Given an instrument "AAPL" on "NASDAQ" already exists
    When I hard-delete the instrument
    Then the registry remains empty
    And no UNIQUE_LOCK exists for ticker "AAPL" on exchange "NASDAQ"
    And looking it up by id fails with code "INSTRUMENT_NOT_FOUND"

  Scenario: Hard delete is idempotent
    When I hard-delete the instrument with id "never-existed"
    And I hard-delete the instrument with id "never-existed"
    Then no error is raised

  # --- additional scenarios beyond §4: documented behaviors not yet in spec --

  @edge-case
  Scenario Outline: List page size is clamped into [1, 200]
    Given 5 active instruments exist
    When I list instruments with status "active" and page size <requested>
    Then <returned> instruments are returned

    Examples:
      | requested | returned |
      | 0         | 5        |
      | -3        | 5        |
      | 1000000   | 5        |

  @edge-case
  Scenario: Archive is idempotent on an already-archived instrument
    Given an instrument "AAPL" on "NASDAQ" already exists
    And the instrument has been archived
    When I archive the instrument
    Then the result has status "archived"

  @edge-case
  Scenario: Restore is idempotent on an already-active instrument
    Given an instrument "AAPL" on "NASDAQ" already exists
    When I restore the instrument
    Then the result has status "active"

  @edge-case
  Scenario: Mutable field names are accepted by the immutability check
    When I attempt to update fields "name,currency"
    Then no error is raised

  @edge-case
  Scenario: Null inputs to register are reported as validation errors
    When I register ticker "<null>" on exchange "NASDAQ"
    Then the operation fails with code "INVALID_TICKER"
    When I register ticker "AAPL" on exchange "<null>"
    Then the operation fails with code "UNSUPPORTED_EXCHANGE"
