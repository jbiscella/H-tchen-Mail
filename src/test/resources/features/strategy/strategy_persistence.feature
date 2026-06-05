Feature: SI-3a — Strategy persistence (STRATEGY item)

  Executable counterpart of CLAUDE.md §2 (STRATEGY item) + Component 1b SI-3a:
  an imported strategy is stored as the verbatim importer JSON in a single
  STRATEGY item per instrument and read back through the production
  serialization (DynamoDbStrategyRepository.toItem / fromItem), so a strategy
  round-trips intact, an instrument with no item keeps legacy detection, and a
  re-save overwrites the single item. The live DynamoDB client round-trip is
  covered by DynamoDbStrategyRepositoryIT (LocalStack).

  Scenario: A saved strategy is read back by instrument id
    Given an imported strategy JSON saved for instrument "abc-123"
    When the strategy repository is queried for instrument "abc-123"
    Then the strategy is returned with every scenario, role and condition intact

  Scenario: An instrument with no STRATEGY item keeps legacy detection
    When the strategy repository is queried for an instrument with no saved strategy
    Then no strategy is returned

  Scenario: Re-saving a strategy for the same instrument overwrites the single item
    Given an imported strategy JSON saved for instrument "abc-123"
    And a different strategy JSON saved for instrument "abc-123"
    When the strategy repository is queried for instrument "abc-123"
    Then the strategy returned is the most recently saved one
