# CLAUDE.md — Heikin Ashi Monitoring Service

This is the authoritative specification document for the project. Claude Code reads this and uses it as the primary source of truth. Specifications are written at the level Claude Code cannot infer on its own; idiomatic Java code, file layout, naming, and library boilerplate are explicitly **delegated** to Claude Code.

## How to read this document

| Section            | What it gives you                                                |
|--------------------|------------------------------------------------------------------|
| ADR                | Stack and architectural decisions, non-negotiable                |
| Data Model         | DynamoDB single-table schema, item types, indexes, TTL           |
| Error Catalog      | All exceptions, classification, and payload shape                |
| Block 1..7         | Behavioral specifications (Gherkin GWT) + reference algorithms   |
| Test Strategy      | Pyramid, scope per block, coverage gates                         |
| Infrastructure     | Resource catalog, deploy pipeline, pre-deploy checklist          |
| Code Style         | Conventions, idioms, anti-patterns                               |
| Appendix           | Consolidated configuration, IAM, observability                   |

The document is **language-agnostic where it can be**. Java/Micronaut/AWS-specific notes appear only where Claude Code might otherwise make a wrong default choice (e.g., Spring vs Micronaut annotations, BigDecimal vs double, JFreeChart specifics).

## Generating a context bundle for chat.com

When the operator says "genera context bundle" or "/context-bundle", 
produce a single markdown file `CONTEXT_BUNDLE.md` in the repo root 
containing:

1. **Project snapshot**: project name, current version, last commit sha, 
   date.
2. **ADR summary**: 5-line recap of the stack from CLAUDE.md.
3. **Implementation status per block**: for each block in CLAUDE.md, 
   one of {not started | in progress | done | drifted from spec}, 
   with a one-line note. "Drifted" requires explanation.
4. **Open TODOs**: list of TODO/FIXME comments in code, with file:line.
5. **Known divergences from CLAUDE.md**: explicit list of decisions 
   made during implementation that contradict or extend the spec, 
   with rationale.
6. **Recent changes**: last 10 git commits with one-line summaries.
7. **Pending questions**: open issues or design questions that need 
   architectural input (these are what I'll discuss in chat.com).
8. **NOT included**: full source code, full test output, dependency 
   tree. Keep the bundle under 500 lines.

After generating, print the file path and a one-paragraph summary 
of what's in it.

---

## 1. Architecture Decision Record (ADR)

| Layer                 | Choice                                                              |
|-----------------------|---------------------------------------------------------------------|
| Language              | **Java 25** (Amazon Corretto)                                       |
| Framework             | **Micronaut 4.10.x** (stable; do not target M5 milestones)          |
| Build tool            | **Maven**                                                           |
| Compute               | **AWS Lambda** (JVM managed runtime + **SnapStart**, no Native Image) |
| Database              | **Amazon DynamoDB** (single-table design, on-demand billing)        |
| AWS SDK               | **AWS SDK v2** (DynamoDB, Bedrock, SES v2, SSM)                     |
| Market data provider  | **EODHD End-of-Day API** for OHLC history, behind the `MarketDataProvider` port |
| News providers        | **Marketaux** + **Yahoo Finance RSS**, aggregated and de-duplicated behind the same port |
| AI provider           | **AWS Bedrock** + **Claude** (model id from config) via `BedrockRuntimeClient.converse(...)` with manual tool-use loop |
| Charting              | **JFreeChart** (`OHLCSeriesCollection` + `CandlestickRenderer`, headless) |
| Email composition     | **Apache Commons Email** (`commons-email2-jakarta`) → MIME built, sent via **SES v2 `sendEmail` with raw content** |
| Validation            | **Jakarta Bean Validation** (`jakarta.validation`)                  |
| JSON                  | **Jackson** (Micronaut Serde default)                               |
| Decimal arithmetic    | **`BigDecimal`** with `MathContext.DECIMAL64`, `RoundingMode.HALF_UP` for display |
| Time                  | **`Instant` UTC** end-to-end; `Clock` injected, never `Instant.now()` directly in business code |
| Logging               | **SLF4J + Logback** with Logstash JSON encoder                      |
| Test                  | **JUnit 5 + Mockito + AssertJ + jqwik (PBT) + Testcontainers + LocalStack** |
| IaC                   | **Terraform** (S3 + DynamoDB lock backend)                          |
| CI/CD                 | **GitHub Actions** with **OIDC** federation to AWS                  |
| Environments          | **single environment** (`prod`)                                     |
| Region (compute/DB)   | `eu-central-1` (configurable)                                       |
| Region (SES)          | `eu-central-1` (configurable, separate variable kept for forward-compat) |

### Architectural principles

| Principle                                | Practical consequence                                          |
|------------------------------------------|----------------------------------------------------------------|
| Hexagonal-ish (ports & adapters)         | `domain` layer depends only on interfaces; adapters in `infrastructure` |
| Immutability by default                  | `record` for domain types and DTOs                             |
| Pure functions for business logic        | HA computation, pattern detection — no I/O, no side effects    |
| Idempotent writes                        | Every persistence op tolerates being replayed                  |
| Async retry over inline retry            | Failed enrichment goes to DynamoDB queue, retried by cron Lambda |
| Fail fast at startup                     | Config validation at context init, not at first invocation     |
| No Native Image                          | JVM + SnapStart is sufficient for daily cadence; AWT charting is not Native-Image friendly |

---

## 2. Data Model

### Table `monitoring`

Single-table design. All entities live here, distinguished by `entity` attribute and `pk`/`sk` patterns.

| Attribute   | Type   | Note                                                  |
|-------------|--------|-------------------------------------------------------|
| `pk`        | String | partition key                                          |
| `sk`        | String | sort key                                               |
| `entity`    | String | discriminator                                          |
| `gsi1Pk`    | String | GSI1 partition key (sparse, only on `INSTRUMENT`)      |
| `gsi1Sk`    | String | GSI1 sort key                                          |
| `gsi2Pk`    | String | GSI2 partition key (sparse, on a due `PENDING_ALERT` (`RETRY_DUE`) or `STRATEGY_PENDING_ALERT` (`RETRY_DUE_STRATEGY`)) |
| `gsi2Sk`    | String | GSI2 sort key (`retry_at` ISO 8601)                    |
| `ttl`       | Number | UNIX epoch seconds; native DynamoDB TTL on this attribute |
| ...         | ...    | entity-specific attributes                             |

**Settings**: On-Demand billing, native TTL on `ttl`, AWS-owned KMS encryption, point-in-time recovery enabled.

### Indexes

| Index                  | PK / SK                                                | Projection | Sparse condition                  |
|------------------------|--------------------------------------------------------|------------|-----------------------------------|
| Primary                | `pk` / `sk`                                            | n/a        | n/a                               |
| GSI1 `gsi_status`      | `gsi1Pk` (`STATUS#<active\|archived>`) / `gsi1Sk` (`INSTRUMENT#<id>`) | ALL  | only items with `entity=INSTRUMENT` |
| GSI2 `gsi_retry_due`   | `gsi2Pk` (`RETRY_DUE` \| `RETRY_DUE_STRATEGY`) / `gsi2Sk` (`<retry_at_iso>`)   | ALL        | items with `entity=PENDING_ALERT` (`RETRY_DUE`) or `entity=STRATEGY_PENDING_ALERT` (`RETRY_DUE_STRATEGY`) |

### Item type catalog

| Entity         | `pk` pattern                       | `sk` pattern                                                  | TTL                                  |
|----------------|------------------------------------|---------------------------------------------------------------|--------------------------------------|
| INSTRUMENT     | `INSTRUMENT#<id>`                  | `META`                                                        | never                                |
| CONFIG         | `INSTRUMENT#<id>`                  | `CONFIG`                                                      | never                                |
| OHLC           | `INSTRUMENT#<id>`                  | `OHLC#<tf>#<bar_time_iso>`                                    | per storage policy                   |
| HA             | `INSTRUMENT#<id>`                  | `HA#<tf>#<bar_time_iso>`                                      | per storage policy                   |
| STRATEGY       | `INSTRUMENT#<id>`                  | `STRATEGY`                                                    | never                                |
| UNIQUE_LOCK    | `TICKER#<exchange>#<ticker>`       | `LOCK`                                                        | never                                |
| PENDING_ALERT  | `PENDING_ALERT#<event_uid>`        | `META`                                                        | grace (e.g. 30 days after creation)  |
| STRATEGY_PENDING_ALERT | `STRATEGY_PENDING_ALERT#<event_uid>` | `META`                                            | grace (e.g. 30 days after creation)  |
| ALERT (audit)  | `INSTRUMENT#<id>`                  | `ALERT#<bar_time_iso>#<pattern>#<subtype>#<sent_at_ms>`       | 365 days                              |

Where `<tf>` is `1d` or `1w`. `<bar_time_iso>` and `<retry_at_iso>` are full ISO 8601 strings (`yyyy-MM-ddTHH:mm:ssZ`).
`<event_uid>` is `<instrument_id>_<tf>_<bar_time>_<pattern>_<subtype>` for a `PENDING_ALERT`; a `STRATEGY_PENDING_ALERT` instead uses `<instrument_id>_<tf>_<bar_time>_strategy` (no subtype, via `PendingStrategyAlert.uidOf`). Both are deterministic concatenations suitable as a composite key.

### INSTRUMENT attributes

| Attribute      | Type    | Required | Notes                                                |
|----------------|---------|----------|------------------------------------------------------|
| `id`           | String  | yes      | UUID v4                                              |
| `ticker`       | String  | yes      | uppercase, no whitespace                             |
| `exchange`     | String  | yes      | uppercase; in supported set                          |
| `name`         | String  | no       | display name                                         |
| `currency`     | String  | no       | ISO 4217                                             |
| `status`       | String  | yes      | `active` \| `archived`                               |
| `created_at`   | String  | yes      | ISO 8601 UTC                                         |
| `updated_at`   | String  | yes      | ISO 8601 UTC                                         |

Supported exchanges (config-driven, may grow): `NASDAQ`, `NYSE`, `MIL`, `XETRA`, `LSE`, `TSX`, `PAR` (Euronext Paris), `AMS` (Euronext Amsterdam).

### CONFIG attributes

| Attribute              | Type        | Required | Notes                                                            |
|------------------------|-------------|----------|------------------------------------------------------------------|
| `storage_policy`       | enum String | yes      | `FULL_HISTORY` \| `ROLLING_WINDOW` \| `SNAPSHOT_ONLY`            |
| `rolling_window_size`  | Number      | cond.    | required if `storage_policy=ROLLING_WINDOW`, ≥ 1                 |
| `tracked_timeframes`   | Set<String> | yes      | non-empty subset of `{"1d","1w"}`                                |
| `patterns`             | Map         | yes      | see structure below; default = all disabled                      |
| `recipients`           | Set<String> | yes      | RFC-valid emails; can be empty (alerts skipped silently then)    |
| `enable_chart`         | Bool        | no       | default `true`                                                   |
| `enable_ai_analysis`   | Bool        | no       | default `true`                                                   |
| `created_at`           | String      | yes      | ISO 8601 UTC                                                     |
| `updated_at`           | String      | yes      | ISO 8601 UTC                                                     |

`patterns` map structure:

```json
{
  "color_change":  { "enabled": false, "min_streak_length": 3 },
  "strong_candle": { "enabled": false, "wick_tolerance": 0.001, "min_body_ratio": 0.5 },
  "doji":          { "enabled": false, "max_body_ratio": 0.1 }
}
```

| Pattern         | Param                | Type    | Range          | Meaning                                                |
|-----------------|----------------------|---------|----------------|--------------------------------------------------------|
| `color_change`  | `min_streak_length`  | int     | ≥ 1            | consecutive same-color candles required before flip    |
| `strong_candle` | `wick_tolerance`     | decimal | ≥ 0            | wick/close ratio below which wick is considered absent |
| `strong_candle` | `min_body_ratio`     | decimal | (0, 1]         | minimum body/range ratio                               |
| `doji`          | `max_body_ratio`     | decimal | (0, 1]         | maximum body/range ratio to qualify as doji            |

### STRATEGY attributes

At most one STRATEGY item per instrument (single `sk = STRATEGY`). Its presence is
what supersedes the three fixed patterns (Block 15, SI-3b). The item stores the
imported strategy as the **verbatim H-tchen JSON** (the `StrategyJsonImporter`
schema, Blocks 15-16); the repository reparses it on read via
`StrategyJsonImporter.fromJson` — no per-field DynamoDB mapping of scenarios /
conditions, so the stored shape always matches the importer's authoritative
schema. The submitted JSON string is stored verbatim in `strategy_json`; reads
return the normalized domain `Strategy` via `StrategyJsonImporter.fromJson`, not
the original bytes (there is no raw-JSON read path).

| Attribute       | Type    | Required | Notes                                                              |
|-----------------|---------|----------|--------------------------------------------------------------------|
| `strategy_json` | String  | yes      | verbatim imported strategy JSON (importer schema, Blocks 15-16)    |
| `name`          | String  | yes      | denormalized strategy name (convenience; authoritative copy is inside `strategy_json`) |
| `created_at`    | String  | yes      | ISO 8601 UTC                                                       |
| `updated_at`    | String  | yes      | ISO 8601 UTC                                                       |

`findByInstrumentId` does a `GetItem` at `pk=INSTRUMENT#<id>, sk=STRATEGY`,
returns empty when absent (the instrument keeps legacy detection), and otherwise
returns `StrategyJsonImporter.fromJson(strategy_json)`. A corrupt stored JSON
fails loud (`StrategyImportException`) rather than silently degrading to legacy.
Writing is idempotent last-write-wins (`PutItem`, no condition): re-importing a
strategy for the same instrument overwrites the single STRATEGY item.

### OHLC attributes

| Attribute      | Type            | Required | Notes                                              |
|----------------|-----------------|----------|----------------------------------------------------|
| `id`           | String          | yes      | UUID v4 (optional convenience; key is `pk`+`sk`)   |
| `instrument_id`| String          | yes      | denormalized                                       |
| `timeframe`    | String          | yes      | `1d` \| `1w`                                       |
| `bar_time`     | String          | yes      | ISO 8601 UTC, normalized                            |
| `open`         | Number          | yes      | > 0; `BigDecimal` in code                           |
| `high`         | Number          | yes      | ≥ low; ≥ open; ≥ close                              |
| `low`          | Number          | yes      | > 0; ≤ open; ≤ close                                |
| `close`        | Number          | yes      | > 0                                                 |
| `volume`       | Number          | no       | ≥ 0                                                 |
| `source`       | String          | yes      | provider tag, e.g. `eodhd`                         |
| `ingested_at`  | String          | yes      | ISO 8601 UTC                                       |
| `ttl`          | Number          | cond.    | per storage policy                                 |

### HA attributes

| Attribute      | Type    | Required | Notes                                              |
|----------------|---------|----------|----------------------------------------------------|
| `instrument_id`| String  | yes      |                                                    |
| `timeframe`    | String  | yes      | mirrors OHLC                                       |
| `bar_time`     | String  | yes      | matches OHLC `bar_time`                            |
| `ha_open`      | Number  | yes      | `BigDecimal`                                        |
| `ha_high`      | Number  | yes      | `≥ ha_low`                                          |
| `ha_low`       | Number  | yes      |                                                    |
| `ha_close`     | Number  | yes      |                                                    |
| `computed_at`  | String  | yes      | ISO 8601 UTC                                       |
| `ttl`          | Number  | cond.    | mirrors OHLC TTL                                   |

### PENDING_ALERT attributes

| Attribute        | Type    | Required | Notes                                                              |
|------------------|---------|----------|--------------------------------------------------------------------|
| `event`          | String  | yes      | full PatternEvent JSON payload                                     |
| `retry_count`    | Number  | yes      | 0..3                                                               |
| `retry_at`       | String  | yes      | ISO 8601 UTC, next attempt                                          |
| `last_error`     | Map     | yes      | `{ code, message, ts }`                                            |
| `created_at`     | String  | yes      | ISO 8601 UTC                                                       |
| `gsi2Pk`         | String  | yes      | `RETRY_DUE`                                                         |
| `gsi2Sk`         | String  | yes      | `<retry_at_iso>`                                                    |
| `ttl`            | Number  | yes      | `now + 30 days` safety net                                          |

### STRATEGY_PENDING_ALERT attributes

A failed **strategy** dispatch (Component 1c, SI-3c.3) queues here instead of the
`PENDING_ALERT` partition, because a strategy alert is not a `PatternEvent`. The
item stores **only the alert + retry bookkeeping — never the chart**: because the
strategy is itself persisted (the `STRATEGY` item) and OHLC/HA bars are readable
by count (`findLastN`), the retry poller **re-renders the chart** from the
persisted `Strategy` + bars rather than carrying a (potentially >400 KB) PNG blob
in the row. If the strategy was deleted between detection and retry, the missing
strategy is treated as a render fault: the item is bumped like any chart failure,
and only on the final attempt (at the retry cap) does the poller send a
chart-degraded email (SI-3c.3).

The item additionally stores the **triggering raw OHLC bar's snapshot** (four
decimals — instrument / timeframe / bar_time come from the alert). The strategy
chart is rendered from the raw `OHLCSeries` (heerwisch draws Heikin-Ashi candle
bodies via `CandleStyle.HEIKIN_ASHI`, §9 Component 1b), so the snapshot is the
raw OHLC, not Heikin-Ashi. Under a `SNAPSHOT_ONLY` retention policy a later
ingest can evict the triggering raw bar before the retry runs (raw OHLC and HA
share the same TTL), so `OhlcRepository.findLastN` no longer contains `bar_time`;
since the entry/exit marker must sit on a bar that is in the series (heerwisch
V7), the poller **synthesizes the triggering bar from this snapshot** when it is
missing. Without it a retried chart would needlessly degrade to chart-less. The
snapshot is a handful of numbers, so it does not reintroduce the 400 KB item-size
risk.

| Attribute       | Type    | Required | Notes                                                                       |
|-----------------|---------|----------|-----------------------------------------------------------------------------|
| `event_uid`     | String  | yes      | `<instrument_id>_<tf>_<bar_time>_strategy` (one pending per instrument/tf/bar) |
| `alert`         | String  | yes      | full StrategyAlert JSON payload (one line per matched scenario)             |
| `trigger_ohlc_open/high/low/close` | Number | no | triggering raw OHLC bar snapshot, for V7-safe bar synthesis on retry (above) |
| `retry_count`   | Number  | yes      | 0..3                                                                        |
| `retry_at`      | String  | yes      | ISO 8601 UTC, next attempt                                                  |
| `last_error`    | Map      | yes      | `{ code, message, ts, component }`                                          |
| `created_at`    | String  | yes      | ISO 8601 UTC                                                                |
| `gsi2Pk`        | String  | yes      | `RETRY_DUE_STRATEGY` (distinct partition so the legacy poller never reads these) |
| `gsi2Sk`        | String  | yes      | `<retry_at_iso>`                                                            |
| `ttl`           | Number  | yes      | `created_at + 30 days` safety net                                           |

### ALERT (audit) attributes

| Attribute         | Type        | Notes                                                              |
|-------------------|-------------|--------------------------------------------------------------------|
| `timeframe`       | String      | `1d` \| `1w`                                                       |
| `bar_time`        | String      | ISO 8601 UTC                                                       |
| `pattern`         | String      |                                                                    |
| `subtype`         | String      |                                                                    |
| `recipients`      | Set<String> | actually delivered (post-SES)                                      |
| `params_used`     | Map         | snapshot of pattern params                                         |
| `ses_message_ids` | Set<String> | one per recipient                                                  |
| `enrichment`      | String      | `full` \| `degraded_chart` \| `degraded_ai` \| `degraded_both`     |
| `sent_at`         | String      | ISO 8601 UTC                                                       |
| `ttl`             | Number      | `bar_time_epoch + 365 days`                                         |

### Storage policy → TTL formula

| Policy              | TTL formula on OHLC and HA bars                                                         |
|---------------------|-----------------------------------------------------------------------------------------|
| `FULL_HISTORY`      | `null` (no TTL set)                                                                     |
| `ROLLING_WINDOW(N)` | `bar_time_epoch + N × period_seconds(tf) + grace_period` (grace = 1 period)             |
| `SNAPSHOT_ONLY`     | optional `null`; the truncation happens via TransactWrite (see Block 3) — TTL is just a safety net |

`period_seconds`: `1d → 86400`, `1w → 604800`.

### Heikin Ashi formulas (canonical reference)

For each bar at chronological index *t*:

```
ha_close[t] = (open[t] + high[t] + low[t] + close[t]) / 4
ha_open[t]  = (ha_open[t-1] + ha_close[t-1]) / 2     # for t ≥ 1
ha_open[0]  = (open[0] + close[0]) / 2               # seed
ha_high[t]  = max(high[t], ha_open[t], ha_close[t])
ha_low[t]   = min(low[t], ha_open[t], ha_close[t])
```

All arithmetic in `BigDecimal` with `MathContext.DECIMAL64`.

---

## 3. Error Catalog

All exceptions extend a `DomainException` hierarchy. Every error has a stable `code` (public contract) and a typed payload. Messages are human-readable and may evolve; clients depend on `code` only.

### Error classification

| Class                       | Retryable? | HTTP-equivalent (if exposed) | Notes                                  |
|-----------------------------|------------|------------------------------|----------------------------------------|
| `ValidationException`       | no         | 400                          | Bad input from caller / config         |
| `NotFoundException`         | no         | 404                          |                                        |
| `ConflictException`         | no         | 409                          | Uniqueness, optimistic concurrency     |
| `TransientException`        | **yes**    | 429/503                      | Throttling, dependencies down, retries |
| `InternalException`         | no         | 500                          | Bug; log + DLQ                         |

### Full catalog

| Code                              | Class             | Raised by                               | Payload                                              |
|-----------------------------------|-------------------|-----------------------------------------|------------------------------------------------------|
| `InvalidTickerException`          | Validation        | register, validate                      | `field`, `value`                                     |
| `UnsupportedExchangeException`    | Validation        | register                                | `value`, `supported`                                 |
| `ImmutableFieldException`         | Validation        | update_metadata                         | `field`                                              |
| `InvalidStoragePolicyException`   | Validation        | update_config                           | `value`, `supported`                                 |
| `MissingWindowSizeException`      | Validation        | update_config                           | —                                                    |
| `InvalidWindowSizeException`      | Validation        | update_config                           | `value`                                              |
| `UnsupportedTimeframeException`   | Validation        | update_config                           | `invalid`, `supported`                               |
| `EmptyTimeframesException`        | Validation        | update_config                           | —                                                    |
| `DuplicateTimeframeException`     | Validation        | update_config (if not normalized)       | `duplicates`                                         |
| `InvalidPatternConfigException`   | Validation        | update_config                           | `pattern`, `field`, `value`                          |
| `InvalidRecipientException`       | Validation        | update_config / SES rejection           | `recipient`                                          |
| `EmptyRecipientsException`        | Validation        | update_config                           | —                                                    |
| `OHLCInvariantViolationException` | Validation        | ingestion (skip, warning)               | `bar_time`, `field`                                  |
| `InstrumentNotFoundException`     | NotFound          | get/update/archive/delete/get_config    | `id`                                                 |
| `TickerNotFoundException`         | NotFound          | provider lookup                         | `ticker`, `exchange`                                 |
| `DuplicateInstrumentException`    | Conflict          | register (TransactWrite cond)           | `ticker`, `exchange`                                 |
| `ConcurrentModificationException` | Conflict          | TransactWrite / ConditionalUpdate       | `resource`                                           |
| `ThrottledException`              | Transient         | DynamoDB throughput / SES throttle       | `retry_after_ms`                                     |
| `ProviderUnavailableException`    | Transient         | market-data / news provider timeout, 5xx, auth, quota | `provider`, `cause`, `retry_after`         |
| `DependencyUnavailableException`  | Transient         | DynamoDB/SES/SSM unreachable            | `dependency`                                         |
| `CircuitOpenException`            | Transient         | per-ticker breaker tripped              | `ticker`                                             |
| `ChartRenderException`            | Transient         | JFreeChart failure                      | `cause`                                              |
| `LLMException`                    | Transient         | Bedrock failure / max iterations / non-JSON output | `cause`                                   |
| `EmailCompositionException`       | Internal          | Commons Email MIME failure              | `cause`                                              |
| `SchemaDriftException`            | Internal          | provider response cannot be parsed (EODHD JSON, Marketaux JSON, Yahoo RSS) | `endpoint`, `payload_sample`      |
| `SESConfigurationException`       | Internal (fatal)  | Sandbox mode in prod, sender unverified | `reason`                                             |
| `MalformedHABarException`         | Internal          | HA bar in DB inconsistent               | `bar_time`                                           |
| `PatternConfigStaleException`     | Internal          | Config references unknown pattern       | `pattern`                                            |
| `HAComputationException`          | Internal          | Decimal overflow / corrupt OHLC         | `bar_time`, `cause`                                  |
| `OrchestrationException`          | Internal          | unhandled                               | —                                                    |

### Warnings (logged, not raised)

| Code                             | Origin                                   |
|----------------------------------|------------------------------------------|
| `HAContinuityBrokenWarning`      | HA seeded mid-stream (SNAPSHOT_ONLY edge case) |
| `RetryQueueExhaustedWarning`     | item processed in degraded mode after 3 attempts |
| `BounceComplaintRecorded`        | SES bounce/complaint webhook (out of v1 scope) |
| `LambdaSoftTimeoutWarning`       | handler approaching 15min timeout, stops queueing new work |

### Logging & redaction rules for errors

| Rule                                                                    |
|-------------------------------------------------------------------------|
| Always log `code`, never log raw stack of `IOException` at INFO         |
| Never log: API keys, full SSM SecureString values, full Bedrock request payload |
| Recipients in logs masked: `a***@example.com`                           |
| `InternalException` log includes `trace_id` for correlation; no PII     |

---

## 4. Block 1 — Instrument Registry (Domain Operations)

**Goal**: Manage the list of tracked instruments. Operations are invoked from a CLI script today and a future web UI; **no REST API**.

```gherkin
Feature: Instrument Registry — Domain Operations

  Background:
    Given the "monitoring" table is empty

  Scenario: Register a valid equity
    When I call register_instrument("aapl", "nasdaq", name="Apple Inc.", currency="USD")
    Then a TransactWrite is executed atomically with:
      | item                                | condition                |
      | UNIQUE_LOCK at TICKER#NASDAQ#AAPL   | attribute_not_exists(pk) |
      | INSTRUMENT meta at INSTRUMENT#<id>  | attribute_not_exists(pk) |
      | CONFIG default at INSTRUMENT#<id>   | attribute_not_exists(pk) |
    And the function returns the created Instrument with generated UUID
    And status="active", created_at=now, updated_at=now
    And gsi1Pk="STATUS#active", gsi1Sk="INSTRUMENT#<id>"

  Scenario: Reject duplicate (ticker, exchange) pair
    Given an instrument "AAPL" on "NASDAQ" already exists
    When I call register_instrument("AAPL", "NASDAQ")
    Then the TransactWrite fails on the UNIQUE_LOCK condition
    And DuplicateInstrumentException is raised with {ticker:"AAPL", exchange:"NASDAQ"}

  Scenario: Reject empty/whitespace ticker
    When I call register_instrument("", "NASDAQ") or register_instrument("AA PL", "NASDAQ")
    Then InvalidTickerException is raised before any DynamoDB call

  Scenario: Normalize ticker and exchange to uppercase
    When I call register_instrument("aapl", "nasdaq")
    Then the persisted ticker is "AAPL" and exchange is "NASDAQ"
    And the lock pk is "TICKER#NASDAQ#AAPL"

  Scenario: Reject unsupported exchange
    Given supported exchanges = config.supported_set
    When I call register_instrument("AAPL", "FOOBAR")
    Then UnsupportedExchangeException is raised

  Scenario: Get instrument by id
    Given an instrument with id "abc-123" exists
    When I call get_instrument("abc-123")
    Then GetItem on pk="INSTRUMENT#abc-123", sk="META" is executed
    And the function returns the Instrument

  Scenario: Get non-existent instrument
    When I call get_instrument("missing-id")
    Then InstrumentNotFoundException is raised with {id:"missing-id"}

  Scenario: List active instruments (default)
    Given 3 active and 1 archived instruments exist
    When I call list_instruments()
    Then a Query on GSI1 with gsi1Pk="STATUS#active" is executed
    And exactly 3 instruments are returned

  Scenario: List archived instruments
    When I call list_instruments(status="archived")
    Then exactly 1 instrument is returned

  Scenario: Pagination
    Given 30 active instruments exist
    When I call list_instruments(page_size=10)
    Then 10 instruments + a "next_cursor" are returned
    When I call list_instruments(page_size=10, cursor=<prev>)
    Then the next 10 instruments are returned

  Scenario: Update mutable metadata
    When I call update_metadata("abc-123", name="Apple Inc.", currency="USD")
    Then UpdateItem on (INSTRUMENT#abc-123, META) sets name+currency+updated_at
    And ticker, exchange, status, created_at are unchanged

  Scenario: Reject update of immutable fields
    When I attempt update_metadata with ticker, exchange, id, or created_at
    Then ImmutableFieldException is raised before any DynamoDB call

  Scenario: Archive an active instrument
    Given an active instrument "AAPL" exists
    When I call archive_instrument("abc-123")
    Then UpdateItem sets status="archived" and gsi1Pk="STATUS#archived"
    And the UNIQUE_LOCK at TICKER#NASDAQ#AAPL is preserved
    And historical OHLC and HA items are preserved

  Scenario: Restore an archived instrument
    When I call restore_instrument("abc-123")
    Then UpdateItem sets status="active" and gsi1Pk="STATUS#active"

  Scenario: Archive non-existent instrument
    When I call archive_instrument("missing-id")
    Then InstrumentNotFoundException is raised

  Scenario: Hard delete instrument with all data
    Given instrument "abc-123" with 100 OHLC + 100 HA items exists
    When I call delete_instrument("abc-123")
    Then a multi-step delete runs:
      | step | operation                                                                  |
      | 1    | Query sk begins_with "OHLC#" or "HA#" + paginated BatchWriteItem delete   |
      | 2    | TransactWrite: DeleteItem META + CONFIG + STRATEGY + UNIQUE_LOCK           |
    And no trace of the instrument remains
    And the STRATEGY item (if any) is gone, so findByInstrumentId returns empty

  Scenario: Hard delete is idempotent
    Given "abc-123" was already deleted
    When I call delete_instrument("abc-123")
    Then the operation succeeds with no items affected
    And no error is raised
```

---

## 5. Block 2 — Per-Instrument Configuration

**Goal**: Configure storage policy, tracked timeframes, pattern detection, and email recipients per instrument.

```gherkin
Feature: Per-Instrument Configuration — Domain Operations

  Background:
    Given an active instrument with id "abc-123" exists

  Scenario: Default config is created together with the instrument
    Given a new instrument is being registered
    When the registration TransactWrite is executed
    Then it also writes a CONFIG item at pk="INSTRUMENT#<id>", sk="CONFIG" with:
      | storage_policy      | ROLLING_WINDOW |
      | rolling_window_size | 200            |
      | tracked_timeframes  | ["1d"]         |
      | patterns            | all disabled (defaults) |
      | recipients          | empty set       |
      | enable_chart        | true            |
      | enable_ai_analysis  | true            |

  Scenario: Get config for an instrument
    When I call get_config("abc-123")
    Then GetItem on (INSTRUMENT#abc-123, CONFIG) is executed
    And the response contains all attributes

  Scenario: Get config for non-existent instrument
    When I call get_config("missing-id")
    Then InstrumentNotFoundException is raised

  Scenario: Switch to FULL_HISTORY
    Given config has storage_policy="ROLLING_WINDOW", rolling_window_size=200
    When I call update_storage_policy("abc-123", "FULL_HISTORY")
    Then UpdateItem sets storage_policy="FULL_HISTORY" and REMOVEs rolling_window_size

  Scenario: Switch to ROLLING_WINDOW with size
    When I call update_storage_policy("abc-123", "ROLLING_WINDOW", window_size=500)
    Then UpdateItem persists both attributes

  Scenario: ROLLING_WINDOW without size
    When I call update_storage_policy("abc-123", "ROLLING_WINDOW")
    Then MissingWindowSizeException is raised

  Scenario: Invalid window size
    When I call update_storage_policy("abc-123", "ROLLING_WINDOW", window_size=0)
    Then InvalidWindowSizeException is raised

  Scenario: Switch to SNAPSHOT_ONLY
    When I call update_storage_policy("abc-123", "SNAPSHOT_ONLY")
    Then UpdateItem sets storage_policy="SNAPSHOT_ONLY" and REMOVEs rolling_window_size

  Scenario: Unknown policy
    When I call update_storage_policy("abc-123", "FOOBAR")
    Then InvalidStoragePolicyException is raised

  Scenario: Set both supported timeframes
    When I call update_timeframes("abc-123", ["1d", "1w"])
    Then UpdateItem persists string set {"1d", "1w"}

  Scenario: Reject unsupported timeframe
    When I call update_timeframes("abc-123", ["1d", "3h"])
    Then UnsupportedTimeframeException is raised

  Scenario: Reject empty timeframes
    When I call update_timeframes("abc-123", [])
    Then EmptyTimeframesException is raised

  Scenario: Duplicates are normalized
    When I call update_timeframes("abc-123", ["1d", "1d"])
    Then the persisted value is the set {"1d"}

  Scenario: Enable color_change with custom streak length
    When I call update_pattern("abc-123", "color_change", {enabled:true, min_streak_length:5})
    Then UpdateItem on patterns.color_change persists {enabled:true, min_streak_length:5}

  Scenario: Disable a pattern
    When I call update_pattern("abc-123", "color_change", {enabled:false})
    Then UpdateItem sets patterns.color_change.enabled=false (other params preserved)

  Scenario: Reject unknown pattern name
    When I call update_pattern("abc-123", "foo", {...})
    Then InvalidPatternConfigException is raised with {pattern:"foo"}

  Scenario: Reject invalid pattern parameter
    When I call update_pattern("abc-123", "color_change", {min_streak_length:0})
    Then InvalidPatternConfigException is raised with {pattern:"color_change", field:"min_streak_length", value:0}

  Scenario: Reject out-of-range float param
    When I call update_pattern("abc-123", "doji", {max_body_ratio:1.5})
    Then InvalidPatternConfigException is raised with {pattern:"doji", field:"max_body_ratio", value:1.5}

  Scenario: Set valid recipients
    When I call update_recipients("abc-123", ["me@example.com", "bot@example.com"])
    Then UpdateItem persists string set {"me@example.com", "bot@example.com"}

  Scenario: Reject invalid email format
    When I call update_recipients("abc-123", ["not-an-email"])
    Then InvalidRecipientException is raised with {recipient:"not-an-email"}

  Scenario: Reject empty recipients list (when explicitly set)
    When I call update_recipients("abc-123", [])
    Then EmptyRecipientsException is raised
    Note: default config starts with empty recipients; alerts skipped silently in that state.

  Scenario: Duplicates are normalized
    When I call update_recipients("abc-123", ["a@x.com", "a@x.com"])
    Then the persisted value is the set {"a@x.com"}

  Scenario: Concurrent updates (last-write-wins)
    Given two concurrent UpdateItem calls on the same CONFIG
    When both succeed
    Then the latest write wins
    Note: optimistic locking via "version" attribute is deferred (see "Deferred / known issues", item 4).
```

---

## 6. Block 3 — Quote Ingestion via EODHD

**Goal**: For each active instrument and each tracked timeframe, fetch closed OHLC bars from the EODHD End-of-Day API, persist idempotently to DynamoDB, apply storage policy.

> **Provider history**: the project originally scraped Yahoo Finance via
> `de.sfuhrm:YahooFinanceAPI`. That was abandoned (unstable, unofficial,
> captcha-prone) in favour of **EODHD**, an official paid API with a clean
> REST/JSON contract. The `MarketDataProvider` port was kept; only the
> adapter changed. Yahoo still appears in the project — but only as a
> *news* source (see "News & fundamentals" below), never for price history.

### Design decisions

| Aspect                          | Choice                                                                                  |
|---------------------------------|-----------------------------------------------------------------------------------------|
| Provider                        | **EODHD End-of-Day API** — `GET https://eodhd.com/api/eod/{symbol}`, `api_token` query-param auth, `fmt=json` |
| Abstraction                     | `MarketDataProvider` port in `domain`; `EodhdMarketDataProvider` (history) in `infrastructure`, exposed via `CompositeMarketDataProvider` |
| API key                         | `monitoring.eodhd.api-key`, set from the `MONITORING_EODHD_API_KEY` env var (no default — boot fails fast if missing) |
| Call budget                     | EODHD free tier is ~20 calls/day — one `fetchHistory` per (instrument, timeframe). Sized for a small watchlist |
| Symbol mapping                  | exchange suffix lookup, **EODHD** codes (table below)                                   |
| Price field                     | EODHD `/eod` returns raw OHLC plus `adjusted_close`; the adapter persists the raw `close` |
| Closed-bar filter               | `bar_time + period_seconds(tf) <= now`                                                  |
| Bootstrap window                | `1d → 250 bars`, `1w → 260 bars` (~5 years), config-driven (`monitoring.bootstrap.*`)   |
| Steady-state range              | from `last_bar.bar_time + 1 period` to `now` (EODHD `from=` query param)                |
| Idempotency                     | conditional Put with `attribute_not_exists(pk)` on (instrument, tf, bar_time)           |
| Timeframe mapping               | `1d → period=d`, `1w → period=w`                                                        |
| Timezone normalization          | `1d → date midnight UTC`; `1w → Monday 00:00 UTC of that week`                          |
| Circuit breaker                 | 3 consecutive failures on same ticker → skip rest of run                                |
| Run-level alarm                 | failure rate > 50% → CRITICAL log + CloudWatch alarm                                    |

### Exchange suffix map (config-driven)

EODHD addresses listings as `TICKER.EXCHANGE_CODE`. The codes are **not**
the same as Yahoo's — EODHD uses `.US` for all US listings, `.LSE` (not
`.L`), `.XETRA` (not `.DE`). The map lives in `monitoring.exchanges.suffix-map`.

| Exchange | EODHD suffix | Example       |
|----------|--------------|---------------|
| NASDAQ   | `.US`        | `AAPL.US`     |
| NYSE     | `.US`        | `IBM.US`      |
| MIL      | `.MI`        | `ENI.MI`      |
| XETRA    | `.XETRA`     | `SAP.XETRA`   |
| LSE      | `.LSE`       | `BP.LSE`      |
| TSX      | `.TO`        | `RY.TO`       |
| PAR      | `.PA`        | `AIR.PA`      |
| AMS      | `.AS`        | `ASML.AS`     |
| SWX      | `.SW`        | `CFR.SW`      |
| BME      | `.MC`        | `SAN.MC`      |

### Brittleness handling

| Failure mode                                  | Behavior                                                       |
|-----------------------------------------------|----------------------------------------------------------------|
| 401 / 403 (bad or expired API token)          | classify as `ProviderUnavailableException`, retry exponential  |
| 429 (daily call quota exhausted)              | classify as `ProviderUnavailableException`, retry exponential  |
| Schema drift (non-JSON, not an array, unmappable bar) | log a sanitized sample, raise `SchemaDriftException`, skip instrument |
| Ticker not found (404)                        | `TickerNotFoundException`, no auto-archive                     |
| Network timeout                               | retry with backoff                                             |
| 3 consecutive failures on same ticker         | circuit-breaker open: skip remainder of run                    |
| > 50% instruments failed                      | CRITICAL log, CloudWatch alarm, run still returns summary      |

### Helper functions

| Function                                                      | Behavior                                              |
|---------------------------------------------------------------|-------------------------------------------------------|
| `period_seconds(tf) -> long`                                  | `1d → 86400`, `1w → 604800`                           |
| `normalize_bar_time(raw, tf) -> Instant`                      | as above                                              |
| `is_closed(bar_time, tf, now) -> boolean`                     | `bar_time + period_seconds(tf) <= now`                 |
| `compute_ttl(config, bar_time, tf) -> Long\|null`             | per storage policy                                    |
| `provider_symbol(ticker, exchange, suffix_map) -> String`     | concatenate ticker with the EODHD exchange suffix     |

### Domain operations (Gherkin)

```gherkin
Feature: Quote Ingestion from EODHD

  Background:
    Given current UTC time is "2026-05-07T22:00:00Z"

  Scenario: Iterate over active instruments
    Given 5 active and 2 archived instruments exist
    When I call ingest_all_active()
    Then ingest_instrument(I) is called once per active instrument
    And archived instruments are skipped

  Scenario: One instrument failure does not block others
    Given 3 active instruments [A, B, C]
    And ingest_instrument(B) raises a non-retryable exception
    When I call ingest_all_active()
    Then ingest_instrument is called for A, B, C
    And the function returns a summary {processed:3, succeeded:2, failed:1}
    And the failure for B is logged with instrument_id and exception details

  Scenario: Aggregated failure rate over threshold raises critical alarm
    Given 10 active instruments and 6 of them fail
    When ingest_all_active() completes
    Then a CRITICAL log entry is emitted with code="HIGH_INGEST_FAILURE_RATE"
    And the function still returns a summary (does not throw)

  Scenario: Iterate over tracked timeframes
    Given an instrument with tracked_timeframes=["1d", "1w"]
    When I call ingest_instrument(I)
    Then ingest_timeframe(I, "1d") is called
    And ingest_timeframe(I, "1w") is called

  Scenario: Bootstrap when no bars exist
    Given instrument "AAPL" on "NASDAQ" with no OHLC bars stored
    And bootstrap_size["1d"] = 250
    When I call ingest_timeframe(I, "1d")
    Then provider.fetchHistory(symbol="AAPL.US", tf="1d", since=now-250d) is called
    And only bars where is_closed(bar_time, "1d", now) is true are kept
    And each bar is persisted with conditional put on (instrument, tf, bar_time)
    And the storage policy is applied

  Scenario: Steady-state fetch from last known bar
    Given the latest stored OHLC bar for ("abc-123", "1d") is bar_time="2026-05-05T00:00:00Z"
    When I call ingest_timeframe(I, "1d")
    Then provider.fetchHistory is called with since="2026-05-06T00:00:00Z"
    And bars for "2026-05-06" (closed) and "2026-05-07" (still in formation) are filtered
    And only "2026-05-06" is persisted

  Scenario: Skip when no new closed bars
    Given the latest stored bar is "2026-05-07T00:00:00Z" and is closed
    When I call ingest_timeframe(I, "1d")
    Then no new bars are persisted
    And the operation succeeds silently

  Scenario: Idempotent re-ingestion
    Given the bar for ("abc-123", "1d", "2026-05-06") is already in DB
    When ingest_timeframe(I, "1d") runs again and EODHD returns the same bar
    Then the conditional put fails on the existing key
    And the failure is treated as a no-op (not an error)

  Scenario: Provider returns empty result
    Given EODHD returns an empty array for the requested period
    When I call ingest_timeframe(I, "1d")
    Then the operation succeeds with 0 bars persisted
    And a debug log entry is emitted

  Scenario: Ticker not found / delisted
    Given EODHD returns HTTP 404 for a symbol
    When I call ingest_timeframe(I, "1d")
    Then TickerNotFoundException is raised (logged at instrument level)
    And the instrument is NOT auto-archived

  Scenario: Transient failure with retry
    Given EODHD returns a timeout, a 5xx, or 429 (quota)
    When I call ingest_timeframe(I, "1d")
    Then up to 3 retries with exponential backoff (1s, 2s, 4s) are attempted
    And on final failure ProviderUnavailableException is raised

  Scenario: Schema drift (EODHD response cannot be parsed)
    Given the response is not a JSON array of bars, or a bar is unmappable
    When ingest_timeframe processes it
    Then SchemaDriftException is raised with a sanitized payload sample logged
    And the instrument is skipped, ingestion continues for others
    And a CRITICAL log signals "EODHD schema drift detected"

  Scenario: Circuit breaker on repeated failures for same ticker
    Given the same ticker has failed 3 times consecutively in this run
    When the 4th attempt would happen
    Then the ticker is short-circuited with code="CIRCUIT_OPEN"
    And no further attempts for this ticker happen until the next run

  Scenario: Daily bar timezone normalization
    Given EODHD returns a daily bar at date "2026-05-06"
    Then normalized bar_time is "2026-05-06T00:00:00Z"

  Scenario: Weekly bar normalization to Monday UTC
    Given EODHD returns a weekly bar anywhere in the week
    Then normalized bar_time is the Monday 00:00 UTC of that week

  Scenario: Reject bar that violates OHLC invariants
    Given a bar with high < low or any price <= 0
    Then it is skipped with OHLCInvariantViolationException logged at warning level

  Scenario: FULL_HISTORY policy
    Given config.storage_policy = "FULL_HISTORY"
    When a new bar is persisted
    Then no ttl attribute is set

  Scenario: ROLLING_WINDOW(N) via TTL
    Given config.storage_policy = "ROLLING_WINDOW", rolling_window_size = 200
    When a new bar with bar_time = "2026-05-06T00:00:00Z" is persisted for tf "1d"
    Then ttl = epoch("2026-05-06T00:00:00Z") + 200 × 86400 + grace_period

  Scenario: SNAPSHOT_ONLY policy with few existing bars
    Given config.storage_policy = "SNAPSHOT_ONLY"
    And there are <25 existing OHLC bars for this (instrument, tf)
    When a new bar is being persisted
    Then a single transactional write atomically deletes existing + puts new
    And after the transaction only the latest bar exists

  Scenario: SNAPSHOT_ONLY with too many existing bars (>25)
    Given >25 existing OHLC bars
    When SNAPSHOT_ONLY is applied
    Then existing bars are deleted in batches of 25
    And the new bar is put with the conditional non-existence check
    Note: not atomic; documented trade-off.

  Scenario: Bootstrap respects storage policy
    Given config.storage_policy = "ROLLING_WINDOW", rolling_window_size = 100
    And bootstrap_size["1d"] = 250
    When ingesting a brand new instrument
    Then 250 bars are fetched and inserted with TTLs based on their respective bar_time
    And TTL will naturally evict the oldest 150 over time

  Scenario: Block 3 only depends on the MarketDataProvider interface
    Given the production wiring binds EodhdMarketDataProvider via CompositeMarketDataProvider
    When ingest_timeframe runs
    Then no EODHD-specific class is referenced outside the eodhd adapter package

  Scenario: Return ingestion summary
    When ingest_all_active() completes
    Then it returns a structured summary with:
      | field         | type | meaning                                     |
      | processed     | int  | total instruments touched                   |
      | succeeded     | int  | instruments where all timeframes succeeded  |
      | failed        | int  | instruments with at least one failure       |
      | bars_inserted | int  | total OHLC bars persisted                   |
      | duration_ms   | long | wall-clock duration                         |
```

### Reference algorithm (per timeframe)

```
ingest_timeframe(I, tf):
  symbol = provider_symbol(I.ticker, I.exchange, suffix_map)   # e.g. "AAPL.US"
  last = query_latest_ohlc(I.id, tf)
  since = last ? last.bar_time + 1 period : now - bootstrap_size[tf] periods

  bars = market_data_provider.fetchHistory(symbol, tf, since)
  bars = filter_closed(bars, tf, now())
  bars = sorted(bars, by=bar_time, ascending)

  for bar in bars:
    if invariants_ok(bar):
      apply_policy_and_put(I, tf, bar, config)
  return inserted_count
```

### MarketDataProvider interface (port)

The interface lives in `domain`. `fetchHistory` is the only method backed by
a real implementation today (EODHD); the fundamentals methods are best-effort
and return empty defaults unless a provider implements them.

| Method                                                              | Returns                                          | Implemented by |
|---------------------------------------------------------------------|--------------------------------------------------|----------------|
| `fetchHistory(symbol, tf, since)`                                   | list of `OHLCBar` (raw, pre-normalization)       | `EodhdMarketDataProvider` |
| `fetchNewsHeadlines(ticker, exchange, max)`                         | list of `NewsHeadline` (title, date, source, url) | `NewsAggregator` (Marketaux + Yahoo RSS) |
| `fetchQuoteInfo(ticker, exchange)`                                  | sector, industry, marketCap, P/E, EPS, beta      | — (empty default) |
| `fetchEarningsCalendar(ticker, exchange)`                           | next + last earnings dates, surprise %           | — (empty default) |
| `fetchRecommendations(ticker, exchange)`                            | analyst rating items                             | — (empty default) |
| `fetchFinancialsSummary(ticker, exchange)`                          | revenue / net income / OCF last 4 quarters       | — (empty default) |
| `fetchInsiderTransactions(ticker, exchange)`                        | recent insider trades                            | — (empty default) |

### News & fundamentals provider composition

`CompositeMarketDataProvider` (the `@Primary MarketDataProvider` bean) wires
the single-purpose adapters together:

- **History** → `EodhdMarketDataProvider` (this block).
- **News** → `NewsAggregator`, which fans `fetchNewsHeadlines` out across
  every enabled `NewsProvider` in parallel. Three ship today:
  `MarketauxNewsProvider` (the Marketaux API, key-authed),
  `YahooFinanceRssNewsProvider` (the Yahoo Finance headline RSS feed, no key,
  browser `User-Agent` required), and `EodhdNewsProvider` (the EODHD News
  API, same token and exchange-suffix map as OHLC ingestion — Block 17).
  A single provider failing is logged and
  dropped — it does not fail the call. Results are merged, de-duplicated (by
  exact URL, or by normalized title within a one-hour window), sorted
  newest-first, and capped. `monitoring.news.providers` enables/disables a
  source without touching code.
- **Quote info / earnings / recommendations / financials / insider trades**
  → not implemented; the interface defaults return empty. The AI tool layer
  (Block 6) treats empty results gracefully.

---

## 7. Block 4 — Heikin Ashi Computation

**Goal**: For each (instrument, timeframe), compute and persist HA bars derived from OHLC, deterministically and idempotently. Triggered immediately after `ingest_timeframe` in the same run.

### Design decisions

| Aspect                              | Choice                                                                                       |
|-------------------------------------|----------------------------------------------------------------------------------------------|
| Mode                                | Incremental (default), bulk recompute available as separate operation                        |
| Initial seed                        | `ha_open[0] = (open[0] + close[0]) / 2`                                                      |
| Numerical                           | `BigDecimal` with `MathContext.DECIMAL64` end-to-end                                          |
| Idempotency                         | Put without condition (overwrite); same OHLC produces same HA → safe re-write                |
| OHLC retro-changes                  | If any ingested OHLC is older than the latest HA, recompute HA from that point forward       |
| Ordering                            | Always ascending by `bar_time`                                                                |
| Storage policy                      | HA mirrors OHLC policy: same TTL formula                                                      |
| SNAPSHOT_ONLY edge case             | HA[t-1] read before truncation; if missing, fall back to seed and emit `HAContinuityBrokenWarning` |

### Domain operations (Gherkin)

```gherkin
Feature: Heikin Ashi Computation

  Background:
    Given the instrument "abc-123" has tracked_timeframes=["1d"]

  Scenario: First-time computation (no prior HA)
    Given there are 5 OHLC bars in DB at times t0 < t1 < t2 < t3 < t4
    And there are no HA bars
    When I call compute_ha_for_timeframe(I, "1d", ingested=[bars at t0..t4])
    Then HA[t0] is computed with seed: ha_open = (open[t0] + close[t0]) / 2
    And HA[t1..t4] are computed iteratively in chronological order
    And 5 HA bars are persisted
    And the function returns the list of computed HA bars

  Scenario: Incremental computation when prior HA exists
    Given the latest HA bar in DB is HA[t10]
    And new OHLC bars at t11, t12 were just ingested
    When I call compute_ha_for_timeframe(I, "1d", ingested=[bars at t11, t12])
    Then HA[t11] is computed using HA[t10] as prev
    And HA[t12] is computed using HA[t11] as prev
    And 2 HA bars are persisted

  Scenario: No new OHLC bars → no-op
    Given ingested is empty
    When I call compute_ha_for_timeframe(I, "1d", ingested=[])
    Then no DynamoDB write is performed
    And the function returns an empty list

  Scenario: Idempotent recomputation when OHLC unchanged
    Given HA[t10] already exists and its underlying OHLC has not changed
    When the same compute is triggered again with ingested=[OHLC at t10]
    Then the recomputed HA[t10] equals the stored HA[t10]
    And Put overwrites with identical values

  Scenario: OHLC retroactively changed
    Given HA[t5..t10] already exist
    And during ingestion OHLC[t8] was overwritten with new values
    When compute_ha_for_timeframe is called with ingested=[OHLC at t8]
    Then the algorithm detects t8 is older than the latest HA (t10)
    And it RECOMPUTES HA[t8], HA[t9], HA[t10] in order
    And those 3 HA bars are overwritten

  Scenario: Gap in OHLC timeline (holiday / weekend)
    Given OHLC bars exist at t10 and t12 (no t11)
    And HA[t10] exists
    When compute_ha_for_timeframe runs with ingested=[OHLC at t12]
    Then HA[t12] is computed using HA[t10] as prev
    And no HA bar is created for t11

  Scenario: HA bar invariants are enforced
    Given an OHLC bar with open=100, high=110, low=95, close=105
    When HA is computed
    Then ha_close = (100+110+95+105)/4 = 102.5 (BigDecimal)
    And ha_high = max(110, ha_open, ha_close)
    And ha_low = min(95, ha_open, ha_close)
    And ha_high >= ha_low always holds

  Scenario: SNAPSHOT_ONLY policy preserves HA chain temporarily
    Given config.storage_policy = "SNAPSHOT_ONLY"
    And HA[t10] is the only HA bar in DB
    When OHLC[t11] is ingested
    Then compute_ha reads HA[t10] BEFORE the snapshot truncation
    And computes HA[t11] = f(HA[t10], OHLC[t11])
    And then a TransactWrite deletes HA[t10] and inserts HA[t11]

  Scenario: SNAPSHOT_ONLY where prev HA was lost mid-stream
    Given an unexpected state: OHLC[t11] exists, HA bar absent
    When compute_ha runs
    Then it falls back to seed and logs HAContinuityBrokenWarning at warning level

  Scenario: FULL_HISTORY → no TTL on HA
    When a HA bar is persisted with config.storage_policy="FULL_HISTORY"
    Then no ttl attribute is set

  Scenario: ROLLING_WINDOW → TTL on HA mirrors OHLC
    Given config.storage_policy="ROLLING_WINDOW", rolling_window_size=200
    When HA[bar_time] is persisted
    Then ttl = epoch(bar_time) + 200 × period_seconds(tf) + grace_period

  Scenario: SNAPSHOT_ONLY → previous HA bars deleted in same TransactWrite
    Given config.storage_policy="SNAPSHOT_ONLY"
    When the new HA bar is persisted
    Then a TransactWrite atomically deletes existing HA bars + inserts new one
    Note: same 25-item transaction limit; chunk via BatchWriteItem if needed.

  Scenario: Bulk recompute from scratch
    When I call bulk_recompute_ha(I, "1d")
    Then all existing HA bars for (I, "1d") are deleted
    And HA is recomputed sequentially from the oldest OHLC bar
    And all HA bars are inserted respecting the storage policy
    And the function returns {ohlc_count, ha_count, duration_ms}

  Scenario: Bulk recompute with no OHLC bars
    When I call bulk_recompute_ha(I, "1d") and there are no OHLC
    Then the operation succeeds with ha_count=0

  Scenario: Function returns list of computed HA bars for downstream pattern detection
    Given compute_ha_for_timeframe persisted HA bars for [t11, t12]
    When the call completes
    Then it returns those 2 HA bars in chronological order
    And the caller (Block 5) uses this list as the "newly available bars to analyze"
```

### Reference algorithm

```
compute_ha_for_timeframe(I, tf, ingested_ohlc):
  if ingested_ohlc is empty: return []

  earliest = min(b.bar_time for b in ingested_ohlc)
  prev_ha = get_ha_immediately_before(I.id, tf, earliest)   # may be null
  ohlc_chain = get_ohlc_range(I.id, tf, from_time=earliest)
  ohlc_chain.sort(by=bar_time, ascending)

  result = []
  for ohlc in ohlc_chain:
    ha = compute_ha(prev_ha, ohlc)            # seed if prev_ha is null
    ttl = compute_ttl(config, ha.bar_time, tf)
    persist_ha(I.id, tf, ha, ttl)             # Put (overwrite)
    result.append(ha)
    prev_ha = ha
  return result
```

---

## 8. Block 5 — Heikin Ashi Pattern Detection

**Goal**: For each newly computed HA bar, evaluate the 3 enabled patterns and produce `PatternEvent`s. Pure function: no I/O except reading historical HA bars.

### Color logic and definitions

| Concept             | Definition                                                         |
|---------------------|--------------------------------------------------------------------|
| GREEN bar           | `ha_close > ha_open`                                               |
| RED bar             | `ha_close < ha_open`                                               |
| NEUTRAL bar        | `ha_close == ha_open` (rare)                                       |
| body                | `abs(ha_close - ha_open)`                                          |
| range               | `ha_high - ha_low`                                                 |
| body_ratio          | `body / range` (defined only if `range > 0`)                       |
| upper_wick          | `ha_high - max(ha_open, ha_close)`                                 |
| lower_wick          | `min(ha_open, ha_close) - ha_low`                                  |
| upper_wick_ratio    | `upper_wick / ha_close`                                            |
| lower_wick_ratio    | `lower_wick / ha_close`                                            |

All in `BigDecimal`. Comparisons via `.compareTo() == 0` (never `.equals()`, which is scale-sensitive).

### Pattern definitions

#### `color_change`

| Sub-event              | Condition                                                                                              |
|------------------------|---------------------------------------------------------------------------------------------------------|
| `bullish_reversal`     | current bar GREEN AND previous N (N=`min_streak_length`) bars are all RED, contiguous in time           |
| `bearish_reversal`     | current bar RED AND previous N bars all GREEN                                                          |

NEUTRAL handling: a NEUTRAL within the streak **breaks** it. Current bar NEUTRAL → no event.

#### `strong_candle`

| Sub-event        | Cumulative conditions                                                                                  |
|------------------|---------------------------------------------------------------------------------------------------------|
| `bullish_strong` | bar GREEN AND `lower_wick_ratio < wick_tolerance` AND `body_ratio >= min_body_ratio`                    |
| `bearish_strong` | bar RED   AND `upper_wick_ratio < wick_tolerance` AND `body_ratio >= min_body_ratio`                    |

If `range == 0` → no event (degenerate, debug log).

#### `doji`

| Sub-event | Condition                          |
|-----------|------------------------------------|
| `doji`    | `body_ratio <= max_body_ratio`     |

If `range == 0` → no event.

### PatternEvent shape

```
PatternEvent {
  instrument_id: String
  ticker:        String      # denormalized for downstream rendering
  exchange:      String      # denormalized
  timeframe:     String      # "1d" | "1w"
  bar_time:      String      # ISO 8601 UTC
  pattern:       String      # "color_change" | "strong_candle" | "doji" | "forced" (synthetic, see §10)
  subtype:       String      # bullish_reversal | bearish_reversal | bullish_strong | bearish_strong | doji | forced
  params_used:   Map         # snapshot of pattern config used
  bar_snapshot:  Map         # ha_open/high/low/close + OHLC values
  detected_at:   String      # ISO 8601 UTC
}
```

A single HA bar may produce multiple events (e.g., color_change + strong_candle simultaneously) — they are independent.

### Domain operations (Gherkin)

```gherkin
Feature: Heikin Ashi Pattern Detection

  Background:
    Given the instrument "abc-123" has tracked_timeframes=["1d"]

  Scenario: No new bars → no events
    When I call detect_patterns(I, "1d", new_ha_bars=[], config)
    Then no DynamoDB read is performed
    And the function returns an empty list

  Scenario: All patterns disabled → no events
    Given config.patterns has all three patterns enabled=false
    And new_ha_bars contains 1 freshly computed HA bar
    When I call detect_patterns(I, "1d", new_ha_bars, config)
    Then no detection logic runs
    And the function returns an empty list

  Scenario: Single Query for HA history (efficiency)
    Given any pattern is enabled
    And new_ha_bars contains M bars
    When detect_patterns is called
    Then a single Query reads the last K HA bars
      (K = max needed = max(min_streak_length over enabled color_change patterns) + M)
    And no per-bar Query is performed

  Scenario: Bullish reversal detected
    Given config.patterns.color_change = {enabled:true, min_streak_length:3}
    And the last 3 historical HA bars are all RED at t8, t9, t10
    And the new HA bar at t11 is GREEN
    When detect_patterns is called with new_ha_bars=[bar at t11]
    Then a PatternEvent is emitted with pattern="color_change", subtype="bullish_reversal"
    And params_used = {min_streak_length: 3}

  Scenario: Bearish reversal detected
    Given the last 4 historical HA bars are all GREEN
    And min_streak_length=3
    And the new HA bar is RED
    Then a PatternEvent with subtype="bearish_reversal" is emitted

  Scenario: Streak too short → no event
    Given the last 2 historical bars are RED, the one before that GREEN
    And min_streak_length=3
    And the new HA bar is GREEN
    Then no color_change event is emitted

  Scenario: NEUTRAL bar in streak breaks it
    Given the last 3 historical bars are [RED, NEUTRAL, RED]
    And min_streak_length=3
    And the new HA bar is GREEN
    Then no color_change event is emitted

  Scenario: Current bar NEUTRAL → no color_change
    Given the new HA bar has ha_close == ha_open
    Then no color_change event is emitted regardless of history

  Scenario: Insufficient history (bootstrap)
    Given only 2 HA bars exist and min_streak_length=3
    Then no color_change event is emitted

  Scenario: Multiple new bars in same run
    Given new_ha_bars contains [t11, t12] in chronological order
    And history at t10/t9/t8 was RED, min_streak_length=3
    And HA[t11] is GREEN, HA[t12] is RED
    When detect_patterns is called
    Then HA[t11] triggers bullish_reversal (3 RED before)
    And HA[t12] does NOT trigger bearish_reversal (only 1 GREEN before in chain: t11)
    And exactly 1 event is emitted

  Scenario: Bullish strong detected
    Given config.patterns.strong_candle = {enabled:true, wick_tolerance:0.001, min_body_ratio:0.5}
    And HA bar: ha_open=100, ha_low=100, ha_close=110, ha_high=112
    Then a PatternEvent with subtype="bullish_strong" is emitted

  Scenario: Bearish strong detected
    Given HA bar: ha_open=100, ha_high=100.05, ha_close=92, ha_low=88
    Then a PatternEvent with subtype="bearish_strong" is emitted

  Scenario: Body too small → no strong_candle
    Given HA bar GREEN with body_ratio=0.3 and min_body_ratio=0.5
    Then no strong_candle event

  Scenario: Wick too large → no strong_candle
    Given HA bar GREEN with lower_wick_ratio=0.005 and wick_tolerance=0.001
    Then no strong_candle event

  Scenario: Range zero → no strong_candle, no doji
    Given HA bar with ha_high == ha_low
    Then no strong_candle and no doji event are emitted (debug log)

  Scenario: Doji detected
    Given config.patterns.doji = {enabled:true, max_body_ratio:0.1}
    And HA bar with body=0.5, range=8, body_ratio=0.0625 <= 0.1
    Then a PatternEvent with pattern="doji", subtype="doji" is emitted

  Scenario: Body too large → no doji
    Given body_ratio=0.4 and max_body_ratio=0.1
    Then no doji event

  Scenario: Multiple patterns on same bar
    Given color_change conditions are met AND strong_candle conditions are also met
    When detect_patterns runs
    Then 2 distinct PatternEvents are emitted for the same bar_time

  Scenario: Detection is deterministic and pure
    Given identical config and identical HA history
    When detect_patterns is called twice
    Then the two calls produce identical PatternEvent lists
    And no DynamoDB write is performed by detect_patterns itself

  Scenario: Event includes denormalized ticker/exchange
    Given an event is emitted for instrument "abc-123" with ticker="AAPL", exchange="NASDAQ"
    Then the PatternEvent contains ticker="AAPL" and exchange="NASDAQ"

  Scenario: Event includes bar_snapshot with both OHLC and HA values
    Then bar_snapshot has fields:
      | field      | source  |
      | open       | OHLC    |
      | high       | OHLC    |
      | low        | OHLC    |
      | close      | OHLC    |
      | volume     | OHLC    |
      | ha_open    | HA      |
      | ha_high    | HA      |
      | ha_low     | HA      |
      | ha_close   | HA      |

  Scenario: Event includes params snapshot for traceability
    Then params_used contains the exact config values used to detect this event
```

---

## 9. Block 6 — Rich Alert Dispatch

**Goal**: For each `PatternEvent`, send a multipart text+HTML email to recipients via SES, with HA chart inline and AI-generated fundamental analysis. Best-effort enrichment with async retry (1h × 3) and fallback to degraded email.

### Pipeline

```
ingest → HA → detect → dispatch_alerts(events)
                          │
                          ├─ render_chart (heerwisch)          ──┐
                          ├─ run_ai_analyst (Bedrock Converse)  ──┤  best-effort
                          ├─ compose_multipart_email (Commons)  ──┤
                          └─ send via SesV2Client.sendEmail(raw)──┘
                          │
                          on failure → write PENDING_ALERT → retry_poller (every 15 min)
                          after 3 retries → degraded email + delete pending
```

### Component 1 — Chart Renderer (heerwisch / ha-track)

Since **Block 14** the chart is rendered by ha-track's **heerwisch** (the
`heerwisch-api` `ChartSpec`/`ChartRenderer`, drawn by the headless
`heerwisch-jfreechart` driver), shared with wichtelm-app. The original custom
JFreeChart renderer is gone; the constraints below now read against heerwisch.

| Aspect                            | Constraint                                                                                |
|-----------------------------------|-------------------------------------------------------------------------------------------|
| HA candles                        | the HA lookback window → a commons `HASeries` via `CommonsBarAdapter`; heerwisch draws HA candles |
| Headless                          | the heerwisch-jfreechart driver is headless and deterministic (embedded DejaVu Sans) — no display required |
| Output                            | PNG bytes from the heerwisch `ChartImage`, mapped unchanged to the domain `ChartImage`     |
| Highlight pattern bar             | `Annotation.BarHighlight` at `bar_time, ha_close` (the bar must be in the series — V7)     |
| Volume sub-panel                  | optional, gated by `monitoring.chart.show-volume` (default `false`)                        |
| Resolution                        | width/height from config; default 900×500                                                  |
| No filesystem                     | All in memory                                                                              |

### Component 1b — Strategy-driven chart overlays (Blocks 15-16 follow-up)

For an instrument monitored by an imported **Strategy** (Blocks 15-16, which
supersedes the three fixed patterns), the chart must additionally explain the
*rule* visually.

**As** an alert recipient of a strategy-monitored instrument, **I want** the
chart to show only the indicators the strategy's scenario conditions reference
(RSI sub-pane, SMA/EMA overlays, MACD sub-pane, …), **so that** the chart
explains the rule that fired — only the relevant diagrams, nothing else.

Derivation is **exclusive** and mirrors wichtelm-app's `HtmlReportGenerator`
(indicator references found by scanning the condition strings for `name(args)`
calls — `dsl-eval` exposes no referenced-indicator API), mapped to
`Indicator.*` on **`PriceSource.CLOSE`** (the raw price, NOT Heikin-Ashi):

| condition references                                                            | → heerwisch Indicator                                            | pane    |
|---------------------------------------------------------------------------------|------------------------------------------------------------------|---------|
| `rsi(p)` / `rsi_overbought(t)` / `rsi_oversold(t)` / `rsi_crosses_50()`         | `RSI(p, ob, os, CLOSE, DANGER_ZONES_ON)` (Tier-B → period 14, thresholds threaded) | SUBPLOT |
| `macd_line/signal/histogram(f,s,sig)` / `macd_*_cross()` / `macd_zero_cross_*()`| `MACD(f,s,sig, CLOSE)` (Tier-B → 12/26/9)                         | SUBPLOT |
| `sma(p)` / `price_*_sma(p)` / `sma_*_ema(sp,ep)`                                 | `SMA(p, CLOSE)` (+ `EMA(ep)` for the MA-vs-MA forms)             | MAIN    |
| `ema(p)` / `price_*_ema(p)`                                                      | `EMA(p, CLOSE)`                                                   | MAIN    |
| `atr(p)`                                                                         | `ATR(p)`                                                          | SUBPLOT |
| `stddev(p)`                                                                      | `StdDev(p, CLOSE)`                                                | SUBPLOT |
| `highest_high(p)` / `lowest_low(p)`                                              | `RollingMax(p, HIGH)` / `RollingMin(p, LOW)`                      | MAIN    |
| `highest_close(p)` / `lowest_close(p)`                                           | `RollingMax(p, CLOSE)` / `RollingMin(p, CLOSE)`                   | MAIN    |
| `ha_*` primitives (doji / strong / reversal)                                     | none (already the HA candles)                                     | —       |

**Heikin-Ashi candles + real-close indicators (ha-track 0.57).** The chart is
built from the instrument's **raw `OHLCSeries`** with
`ChartSpecBuilder.withCandleStyle(CandleStyle.HEIKIN_ASHI)`: heerwisch computes
Heikin-Ashi for the **candle bodies only** (display), while the indicators are
computed on the raw series — `PriceSource.CLOSE`. This is required for the chart
to *justify the rule*: the rule engine (`DslConditionEvaluator`) evaluates every
Tier-A indicator (`rsi`/`sma`/`ema`/`macd`/`stddev`, `highest_*`/`lowest_*`) on
raw OHLC, never on Heikin-Ashi values. Drawing the overlays on HA close (the
pre-0.57 behaviour, which fed heerwisch an `HASeries` and was forced to
`HA_CLOSE` by heerwisch's V5 invariant) made the chart contradict the alert: an
RSI computed on smoothed HA closes need not cross the threshold the rule named
on the raw close. `CandleStyle.HEIKIN_ASHI` (added in heerwisch 0.57) decouples
candle style from indicator price source, so the candles stay Heikin-Ashi while
the indicators read the real close exactly as the rule evaluated them. The
window-aggregate channels read raw `HIGH` / `LOW` / `CLOSE` to match
`highest_high` / `lowest_low` / `highest_close` / `lowest_close`.

Indicators are de-duplicated by `indicator.toString()`; an indicator referenced
by several scenarios is drawn once.

Increments:
- **SI-1** — pure derivation `Strategy → List<Indicator>` (`StrategyChartIndicators`). DONE.
- **SI-2** — build the `ChartSpec` for a `StrategyAlert` from the derived indicators + a role-direction marker on the trigger bar (`StrategyChartSpec`). DONE.
- **SI-3b** — suppression: an instrument with a strategy no longer emits the legacy fixed-pattern alerts (Block 15). DONE.
- **SI-3c.1** — render the `StrategyChartSpec` to PNG via a dedicated `StrategyChartRenderer` (domain port) backed by the heerwisch driver.
- **SI-3c** — dedicated dispatch of a `StrategyAlert`: chart (SI-3c.1) → AI → email → retry/audit, via `StrategyAlert`-aware port overloads (dispatch fork "A", faithful: preserves all matched lines / roles / memo).
- **SI-3 (orchestration)** — `MonitoringRunService` calls `detectStrategyAlert` for strategy instruments and routes the `StrategyAlert` to the dedicated dispatch. Placement rule (parity with legacy): detection and the chart-lookback read run INSIDE the per-instrument try/catch; the `strategyDispatchService.dispatch(...)` call (real and forced alerts alike) runs OUTSIDE it, next to the legacy `dispatchAlerts`, so an audit-write or retry-enqueue persistence failure fails the invocation (Lambda retry/DLQ) instead of being swallowed as an instrument failure. Evaluation merges the freshly ingested OHLC bars into the persisted `findLastN` series, so an eventually-consistent read can never make the strategy evaluate a stale latest bar (Block 16 SI-3 scenarios).
- **SI-3a** — strategy persistence: a `STRATEGY` item (§2) + `DynamoDbStrategyRepository` replacing the `NoOp`. The item stores the verbatim importer JSON; `findByInstrumentId` reparses via `StrategyJsonImporter.fromJson`, `save` is an idempotent `PutItem`.
- **SI-3c.3** — retry for strategy alerts: a `STRATEGY_PENDING_ALERT` item (§2) storing the StrategyAlert JSON (no chart), enqueued on a failed dispatch and recovered by a `StrategyRetryPollerService` that **re-renders** the chart from the persisted `Strategy` + bars, re-runs AI, re-sends, audits, and degrades after the cap. See Component 1c "Retry for strategy alerts".

**Trigger marker (display-only).** The matched bar carries a direction marker
derived from each matched line's `role`, following wichtelm's capital-flow
matrix. This is a *display* mapping only — the role stays verbatim in the email
text (Block 16) and is never used for a trade decision. An unrecognized role
falls back to a neutral `Annotation.BarHighlight` at `bar_time, close` (raw
close; the chart is built from the raw `OHLCSeries`, with Heikin-Ashi candle
bodies drawn by `CandleStyle.HEIKIN_ASHI` — §9 Component 1b).

| role          | `MarkerDirection` | `GlyphStyle`    |
|---------------|-------------------|-----------------|
| `long_entry`  | `LONG_ENTRY`      | `UP_TRIANGLE`   |
| `short_entry` | `SHORT_ENTRY`     | `DOWN_TRIANGLE` |
| `long_exit`   | `LONG_EXIT`       | `DOWN_TRIANGLE` |
| `short_exit`  | `SHORT_EXIT`      | `UP_TRIANGLE`   |
| (other)       | — neutral `BarHighlight` | —        |

Indicators are placed by each indicator's `defaultPane()` (MAIN overlays in
place; oscillators cycle the subplot slots), de-duplicated, and any whose period
exceeds the window are skipped — the same placement loop the legacy
`HeerwischChartRenderer` already uses.

#### Domain operations (Gherkin)

```gherkin
Feature: Strategy-driven chart overlays

  Scenario: Only the referenced oscillator is derived; HA primitives add nothing
    Given a strategy whose single scenario references "rsi(14) crosses below 30" and "ha_bullish_reversal(3)"
    When the chart indicators are derived
    Then the derived indicators are exactly RSI(14) in a sub-pane

  Scenario: An indicator referenced by several scenarios is derived once
    Given a strategy with two scenarios referencing macd_bullish_cross() and macd_bearish_cross()
    When the chart indicators are derived
    Then the derived indicators are exactly MACD(12,26,9)

  Scenario: Moving averages become main-pane overlays
    Given a strategy referencing sma(50) and ema(200)
    When the chart indicators are derived
    Then the derived indicators are exactly SMA(50) and EMA(200), both in the MAIN pane

  Scenario: A pure Heikin-Ashi strategy adds no overlays
    Given a strategy referencing only ha_doji() and ha_strong_bullish()
    When the chart indicators are derived
    Then no chart indicators are derived

  # SI-2 — the chart spec built for a strategy alert

  Scenario: The strategy chart spec carries the derived overlays in their panes
    Given a strategy referencing rsi(14) and sma(50)
    And a strategy alert with a "long_entry" line on the latest bar
    When the strategy chart spec is built
    Then the spec places RSI(14) in a sub-pane and SMA(50) in the MAIN pane

  Scenario: A long_entry line places an up-triangle entry marker on the trigger bar
    Given a strategy referencing rsi(14)
    And a strategy alert with a "long_entry" line on the latest bar
    When the strategy chart spec is built
    Then the spec has an entry marker on the trigger bar with direction "LONG_ENTRY" and glyph "UP_TRIANGLE"

  Scenario: A long_exit line places a down-triangle exit marker on the trigger bar
    Given a strategy referencing macd_bearish_cross()
    And a strategy alert with a "long_exit" line on the latest bar
    When the strategy chart spec is built
    Then the spec has an entry marker on the trigger bar with direction "LONG_EXIT" and glyph "DOWN_TRIANGLE"

  Scenario: An unrecognized role falls back to a neutral bar highlight
    Given a strategy referencing rsi(14)
    And a strategy alert with a "watch" line on the latest bar
    When the strategy chart spec is built
    Then the spec has a neutral bar highlight on the trigger bar and no entry marker

  # SI-3c.1 — render the spec to PNG via the heerwisch driver

  Scenario: The strategy alert chart renders to a PNG the email can embed
    Given a strategy referencing rsi(14) and sma(50)
    And a strategy alert with a "long_entry" line on the latest bar
    When the strategy alert chart is rendered
    Then a PNG chart image of 900x500 is produced

  # SI-3a — strategy persistence: the STRATEGY item round-trips through the repository

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
```

### Component 1c — Strategy alert dispatch (SI-3c)

A `StrategyAlert` is dispatched by a dedicated `StrategyAlertDispatchService`
that mirrors the legacy `AlertDispatchService` but is `StrategyAlert`-aware (the
faithful "path A"): render chart (SI-3c.1) → AI → email, preserving every matched
line / role / memo. The AI and email ports gain `StrategyAlert` overloads
(`AiAnalyst.analyze(StrategyAlert)`, `EmailSender.sendFull(StrategyAlert, …)`);
the email bodies show one line per matched scenario with the role and the
verbatim stop/take/precondition memo (Block 16), never collapsed to a single
pattern.

SI-3c.2 covers the first-attempt happy path + the no-recipients skip.

```gherkin
Feature: Strategy alert dispatch (first attempt)

  Scenario: A strategy alert is dispatched with chart, AI and email
    Given an instrument with one recipient monitored by a strategy
    And a strategy alert matched on the latest bar
    When the strategy alert is dispatched
    Then the strategy chart is rendered
    And the AI analyst runs for the strategy alert
    And a full email is sent to the recipient
    And the dispatch counts {sent:1}

  Scenario: A strategy alert with no recipients is skipped
    Given an instrument with no recipients monitored by a strategy
    And a strategy alert matched on the latest bar
    When the strategy alert is dispatched
    Then no email is sent
    And the dispatch counts {skipped:1}
```

#### Retry for strategy alerts (SI-3c.3)

A failed strategy dispatch is **retried**, mirroring the legacy `PENDING_ALERT`
mechanics (§9 "Retry queue mechanics"). Because the strategy is persisted (the
`STRATEGY` item, SI-3a) and bars are readable by count (`findLastN`), the queued
`STRATEGY_PENDING_ALERT` item (§2) stores **only the alert** — never the chart.
The poller **re-renders** the chart from the persisted `Strategy` + bars (falling
back to chart-degraded if the strategy was deleted), re-runs the AI analyst from
the stored alert, and re-sends. This keeps the row small and removes the
DynamoDB 400 KB item-size risk of inlining a PNG.

| Behavior                                       | Rule                                                                              |
|------------------------------------------------|-----------------------------------------------------------------------------------|
| First transient failure (chart / AI / email)   | write `STRATEGY_PENDING_ALERT` with `retry_count=0`, `retry_at=now+1h`, `last_error.code` (no chart stored) |
| Poller picks up due items                       | Query GSI2 with `gsi2Pk=RETRY_DUE_STRATEGY` and `gsi2Sk <= now`                   |
| Poller, per item                                | **claim first** (lease, as legacy §9): conditional `retry_count++` bump — only the claim winner proceeds; then reload `Strategy` + bars and **re-render** the chart (chart-degraded if the strategy is gone or render still fails); re-run `AiAnalyst.analyze(alert)`; send |
| On success (claim won)                          | record the audit `ALERT` item; DeleteItem `STRATEGY_PENDING_ALERT`               |
| On failure with claimed `retry_count < 3`       | update `last_error` in place (conditional write at the claimed `retry_count`, no second increment); `retry_at` stays as set by the claim |
| On failure with claimed `retry_count == 3`      | Send **degraded** email (chart-degraded if render still fails; AI section empty if AI still fails); DeleteItem |
| All recipients rejected on the **final** attempt | DeleteItem and give up — an invalid recipient list is permanent, so the poison item is dropped (logged) rather than bumped forever. SES being *down* (a `DependencyUnavailableException`) is transient and still bumps. |
| No recipients at poll time                       | DeleteItem and skip                                                              |
| Idempotency / races                             | enqueue `attribute_not_exists(pk)`; claim-before-processing lease conditional on `retry_count` — same as legacy (§9 "Retry queue mechanics"): two pollers that read the same item snapshot send exactly one email; failures after the claim update `last_error` in place without a second increment |

**Chart window.** Both the first-attempt dispatch and the retry re-render read
the chart's HA bars by the **same lookback as strategy evaluation**
(`STRATEGY_LOOKBACK_BARS`), so any indicator a scenario references (and that was
warm enough to fire the alert) has enough bars to render — otherwise
`StrategyChartSpec.placeIndicators` would silently drop the very overlay that
triggered the alert.

**Render-fault wrapping.** `HeerwischStrategyChartRenderer` wraps **any** runtime
failure (heerwisch/JFreeChart `RuntimeException`, not only the driver's checked
`ChartRenderException`) as the domain `ChartRenderException`, so a render fault is
caught by the chart-stage handler and enqueued/degraded rather than failing the
whole instrument run — same contract as the legacy `HeerwischChartRenderer`.

**SES-fault wrapping.** `SesEmailSender.sendRaw` classifies a send fault by type.
A non-`SesV2Exception` AWS-SDK `SdkException` (client-side timeout / network /
credentials) is treated as a transient `DependencyUnavailableException`, so it is
caught by dispatch / poller and enqueued / bumped instead of escaping. A
`SesV2Exception` is split by error code via `classify`: transient throttling / 5xx
→ `DependencyUnavailableException` (retried); config / account errors
(`AccessDeniedException`, `SendingPausedException`, anything `NotAuthorized`) →
`SESConfigurationException` (surfaced, not retried); recipient-level rejects
(`MessageRejected`, `MailFromDomainNotVerified`, address-format) → a failed
`DeliveryResult` with no exception, handled as an ordinary non-delivery.

**Audit.** A successful strategy send (first attempt *and* retry) records an
`ALERT` audit item (§2) via `AlertAuditRepository.recordSentStrategyAlert`, reusing
the legacy `ALERT` shape with `pattern = "strategy"`, `subtype = <strategy name>`,
the delivered recipients, SES message-IDs, and enrichment — so strategy sends
appear in the same compliance history as legacy alerts. On the **retry** path the
audit write is **best-effort and happens AFTER the pending item is deleted**: the
email is already delivered, so a transient audit-write failure must not leave the
pending item to be retried and re-send a duplicate. A failed audit write is logged
and swallowed, never resurfacing the alert.

**Retry failure attribution.** When a retry bumps the pending item, `last_error`
names the dependency that actually failed: a chart re-render failure →
`component = chart`, an AI failure → `ai`, and a **mail-send failure (SES
unavailable, or all recipients rejected) → `email`** — so retry-backlog
diagnostics and alarms point at the right dependency rather than mislabeling a
mail outage as an AI failure.

**Handler isolation.** The `retry-poller` Lambda (§10) runs **both** queues each
tick, each in its own guarded block: a runtime failure draining the legacy
`PENDING_ALERT` batch does not abort the run before the `STRATEGY_PENDING_ALERT`
batch (and vice-versa).

```gherkin
Feature: Strategy alert retry

  Scenario: A transient AI failure on first attempt enqueues a strategy pending alert
    Given an instrument with one recipient monitored by a strategy
    And a strategy alert matched on the latest bar
    And the AI analyst fails on the next 1 attempt
    When the strategy alert is dispatched
    Then no strategy email is sent
    And a strategy pending alert is enqueued with retry_count 0
    And the strategy dispatch counts queued 1

  Scenario: The poller re-renders the chart and re-sends
    Given a strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 0 due now
    When the strategy retry poller runs
    Then the strategy chart is re-rendered from the persisted strategy
    And a full strategy email is sent
    And the strategy pending alert is deleted

  Scenario: A missing strategy at retry degrades to a chart-less email after the cap
    Given no strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 2 due now
    When the strategy retry poller runs
    Then a degraded strategy email is sent without a chart
    And the strategy pending alert is deleted

  Scenario: A transient failure under the cap bumps the retry count
    Given a strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 0 due now
    And the AI analyst fails on the next 1 attempt
    When the strategy retry poller runs
    Then no strategy email is sent
    And the strategy pending alert retry_count is 1

  Scenario: All recipients rejected on the final attempt drops the poison item
    Given a strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 2 due now
    And the email sender will reject recipient "alice@example.com"
    When the strategy retry poller runs
    Then the strategy pending alert is deleted

  Scenario: Double strategy poller execution on the same due item sends exactly one email
    Given a strategy is persisted for the instrument
    And a strategy pending alert is queued with retry_count 0 due now
    When the strategy retry poller runs twice on the same snapshot
    Then exactly one full strategy email is sent
    And the strategy pending alert is deleted
    And the losing invocation is a complete no-op
```

### Component 2 — AI Analyst (Bedrock Converse + tool-use loop)

Tool catalog (each backed by `MarketDataProvider`):

| Tool                          | Description                                                            |
|-------------------------------|------------------------------------------------------------------------|
| `get_quote_info`              | sector, industry, market cap, P/E, EPS, beta, dividend yield           |
| `get_earnings_calendar`       | next earnings date, last earnings + surprise %                         |
| `get_news_headlines`          | recent N headlines with date and source                                |
| `get_recommendations`         | recent analyst ratings                                                 |
| `get_financials_summary`      | revenue / net income / OCF last 4 quarters                             |
| `get_insider_transactions`    | recent insider trades                                                  |

Each tool: 5-second timeout, in-memory cache for the duration of a single AI invocation, payload truncation (top 5 items, summarized).

Prompt structure (model-agnostic):

```
SYSTEM:
You are a financial analyst writing the fundamental-analysis note attached to a
detected Heikin Ashi pattern alert. Use the tools to fetch the data you judge
relevant, then produce JSON only with the schema below.

CORROBORATING — cite up to 5 news items that justify the detected pattern,
ranked by: (1) recency — within 7 days of bar_time first; (2) direction
alignment — content/sentiment matching the pattern direction; (3) specificity
— company-specific over sector-wide; (4) material impact — earnings / M&A /
guidance / regulatory over analyst opinion over generic sector news. Cite
fewer than 5 if fewer pass; if none are relevant, say so rather than padding.

CONTRADICTING — fundamentals that weaken the signal. When a useful source
returned nothing, explain why that data would have mattered for THIS pattern
— not just "data not available".

DATA_SOURCES — every tool called, formatted "name(count)" so coverage is
transparent (e.g. "news_headlines(5)", "recommendations(0)").

Be honest about limited information; never invent facts. No strict length
limit — prioritise information density. Output the JSON object only.

FORMAT (readability increment, 2026-06-12): "corroborating" and
"contradicting" are each a single string of flowing prose — never JSON,
arrays, key=value pairs, or bullet/numbered lists — but distinct stories or
themes MUST be separated by a blank line (a paragraph break inside the
string, written as the escaped \n\n sequence valid inside a JSON string —
never a raw line break, which would make the JSON invalid). One continuous
wall of text is as unacceptable as a bullet list.

Parser resilience (Codex P2 on PR #85): even with the escaped-sequence
instruction, a model may emit a literal line break inside the string. The
response parser MUST tolerate unescaped control characters inside JSON
string literals (Jackson ALLOW_UNESCAPED_CONTROL_CHARS) so a raw paragraph
break degrades to nothing worse than a correctly parsed newline — the AI
note must never be lost to the readability instruction itself.

USER:
Pattern detected:
  instrument: <ticker> on <exchange>
  timeframe: <tf>
  bar_time: <ISO date>
  pattern: <pattern> / <subtype>
  HA values: ha_open=..., ha_close=..., ha_high=..., ha_low=...
  OHLC values: open=..., high=..., low=..., close=...

Decide which tools to call, then write the note as JSON only.
```

> **Superseded by Block 18 (Part A).** The USER message above is the pre-Block-18
> shape and carries only the single alert bar. Block 18 adds the chart's lookback
> series and its resolved indicator set to both this message and the
> `StrategyAlert` variant (which today carries no bar values at all). It also
> corrects a drift in this block: the `OHLC values` line previously listed
> `open, close, volume`, whereas the code sends `open, high, low, close` and no
> volume.

Required JSON output schema:

```json
{
  "corroborating": "string, optional",
  "contradicting": "string, optional",
  "confidence":   "LOW | MEDIUM | HIGH",
  "data_sources": ["news_headlines(5)", "recommendations(0)", "..."]
}
```

Loop semantics for `BedrockRuntimeClient.converse(...)`:

| Step | Action                                                                                                    |
|------|-----------------------------------------------------------------------------------------------------------|
| 1    | Init request with `system`, `messages`, `toolConfig`, `inferenceConfig` (max_tokens from config)         |
| 2    | `client.converse(request)`                                                                                |
| 3    | Inspect `stopReason`: `END_TURN` → final; `TOOL_USE` → loop; `MAX_TOKENS` → `LLMException`                |
| 4    | On `TOOL_USE`: execute each `toolUse` block locally, build `toolResult` blocks, append to `messages`, re-send |
| 5    | Cap iterations from `monitoring.bedrock.max-tool-iterations` (default 8). If hit, force final request **without** `toolConfig` to obtain a wrap-up answer |
| 6    | Final response must contain a text block with valid JSON matching the schema; parse error → `LLMException` |
| 7    | Throttling: rely on AWS SDK v2 retry policy (`RetryPolicy.builder().numRetries(3)`)                       |

### Component 3 — Email Composer (Apache Commons Email + SES SendRawEmail)

Pattern (do **not** use Commons Email's SMTP send):

| Step | Action                                                                                  |
|------|-----------------------------------------------------------------------------------------|
| 1    | Build `HtmlEmail` with sender, single recipient, subject                                 |
| 2    | `String cid = email.embed(pngBytes, "chart.png")` to get the CID                        |
| 3    | `email.setHtmlMsg(html)` with `<img src="cid:" + cid + "">` interpolated                |
| 4    | `email.setTextMsg(plainText)` (text-only fallback)                                       |
| 5    | `MimeMessage mm = email.buildMimeMessage()` (do NOT call `email.send()`)                |
| 6    | `mm.writeTo(byteStream)` → raw bytes                                                    |
| 7    | `SesV2Client.sendEmail(SendEmailRequest.builder().content(EmailContent.builder().raw(RawMessage.builder().data(SdkBytes.fromByteArray(raw)).build()).build()).build())` |

Constraints:
- one `HtmlEmail` per recipient (no BCC)
- total message ≤ 10 MB (SES limit)
- charset UTF-8 explicit on both bodies

### Subject template

```
[HA Alert] {ticker}.{exchange} — {pattern}/{subtype} on {timeframe} ({bar_date})
```

Example: `[HA Alert] AAPL.NASDAQ — color_change/bullish_reversal on 1d (2026-05-07)`

Subject prefix is configurable via `monitoring.email.subject-prefix`.

### Plain text body template

```
Heikin Ashi pattern detected.

Instrument:   {ticker} on {exchange}
Timeframe:    {timeframe}
Bar time:     {bar_time}
Pattern:      {pattern}
Subtype:      {subtype}

Heikin Ashi values:
  ha_open  = {ha_open}
  ha_high  = {ha_high}
  ha_low   = {ha_low}
  ha_close = {ha_close}

Underlying OHLC:
  open   = {open}
  high   = {high}
  low    = {low}
  close  = {close}
  volume = {volume}

Detection parameters:
  {param_name_1} = {param_value_1}
  ...

Detected at: {detected_at}
Instrument id: {instrument_id}
```

(Decimal formatting: 2 decimal places by default for prices; integer with thousands separator for volume.)

### HTML body template (structural)

```html
<html><body style="font-family: -apple-system, sans-serif; max-width: 600px;">
  <h2>Heikin Ashi Pattern Detected</h2>
  <p><b>{ticker}.{exchange}</b> — {pattern} / {subtype} on {timeframe} ({bar_date})</p>
  <img src="cid:{chart_cid}" alt="Heikin Ashi chart" style="max-width:100%;">
  <h3>Pattern values</h3>
  <table>...HA + OHLC...</table>
  <h3>AI fundamental analysis (confidence: {confidence})</h3>
  <p><b>Corroborating:</b> {ai.corroborating}</p>
  <p><b>Contradicting:</b> {ai.contradicting}</p>
  <p style="font-size:11px;color:#888">Data sources: {ai.data_sources}</p>
  <hr>
  <p style="font-size:11px;color:#888">
    Detected at {detected_at}. Instrument id: {instrument_id}.
  </p>
</body></html>
```

**Paragraph rendering (readability increment, 2026-06-12).** The AI note's
`corroborating` / `contradicting` strings may contain newlines as paragraph
separators (the FORMAT prompt instruction above asks the model to separate
distinct stories/themes with a blank line). The HTML body MUST preserve them:
after HTML-escaping, `\n` is converted to `<br>` — raw newlines collapse to a
space in HTML, which produced unreadable wall-of-text notes. Escaping runs
BEFORE the `<br>` substitution, so no markup can be injected through model
output. The plain-text body already carries newlines verbatim and needs no
conversion.

### Degraded email rules (after 3 failed retries)

| Component failed   | HTML substitution                                                            |
|--------------------|------------------------------------------------------------------------------|
| Chart              | `<p><i>[Chart unavailable] Pattern: {subtype}</i></p>` (no `<img>`)         |
| AI                 | "AI fundamental analysis" header includes `(unavailable)`; corroborating/contradicting fields **empty** |
| Both               | Both substitutions applied                                                   |

### Retry queue mechanics

`PENDING_ALERT` items live in DynamoDB. The retry poller is a separate Lambda triggered every 15 min by EventBridge.

| Behavior                                     | Rule                                                                          |
|----------------------------------------------|-------------------------------------------------------------------------------|
| First failure                                | write `PENDING_ALERT` with `retry_count=0`, `retry_at=now+1h`, `last_error.code` |
| Poller picks up due items                    | Query GSI2 with `gsi2Pk=RETRY_DUE` and `gsi2Sk <= now` (ISO comparison string) |
| Claim before processing (lease)              | BEFORE any chart/AI/send work, the poller claims the item: UpdateItem `retry_count++`, `retry_at = now + 1h` with `ConditionExpression="retry_count = :expected"`. Only the claim winner processes the item; a loser (stale `retry_count`, or item already deleted) is a complete no-op — no email, no write, not counted as processed |
| On success (claim won)                       | DeleteItem `PENDING_ALERT`                                                     |
| On failure with claimed `retry_count < 3`    | update `last_error` in place (conditional write at the claimed `retry_count`, **no second increment** — the attempt was already consumed by the claim); `retry_at` stays as set by the claim |
| On failure with claimed `retry_count == 3`   | Send **degraded** email with whatever components succeeded; DeleteItem `PENDING_ALERT` |
| Idempotency under double execution           | the conditional claim fences the WHOLE attempt, send included: two pollers that read the same item snapshot produce exactly one email and one state transition |
| Crash safety                                 | a crash after the claim leaves the item scheduled at `retry_at = now + 1h` with one attempt consumed — at-least-once delivery preserved (never claim-by-delete) |
| SES recipient failure (per-recipient)        | NOT enqueued (recipient-level), just logged                                    |

### Domain operations (Gherkin)

```gherkin
Feature: Rich Alert Dispatch

  Background:
    Given the instrument "abc-123" with ticker="AAPL", exchange="NASDAQ" exists
    And its config.recipients = ["alice@example.com"]
    And SES sender is verified, production access enabled
    And Bedrock model and IAM permissions are configured

  Scenario: Single event, all enrichments succeed
    Given a PatternEvent for color_change/bullish_reversal on AAPL
    When dispatch_alerts([event]) is called
    Then chart_renderer is called and produces PNG bytes
    And ai_analyst runs the tool-use loop and returns valid JSON
    And email_composer builds multipart with text+html+image
    And SES delivers the message
    And no PENDING_ALERT is written
    And the function counts {sent:1, failed:0}

  Scenario: Chart rendering fails on first attempt
    Given JFreeChart raises an exception
    When dispatch_alerts([event]) is called
    Then no email is sent yet
    And a PENDING_ALERT is written with retry_at=now+1h, retry_count=0
    And last_error.code="ChartRenderError"
    And the function counts {sent:0, queued:1}

  Scenario: Chart fails 3 times → degraded email sent
    Given a PENDING_ALERT exists with retry_count=2 and retry_at=now-1m
    When the retry_poller runs and chart still fails
    Then a degraded email is sent (no chart, placeholder text)
    And the AI analysis section is included normally if AI succeeded
    And the PENDING_ALERT is deleted

  Scenario: AI fails on first attempt
    Given Bedrock returns ThrottlingException
    Then no email is sent yet, PENDING_ALERT written with last_error.code="LLMError"

  Scenario: AI fails 3 times → degraded email
    Given retry_count=2, retry_at expired, Bedrock still fails
    Then a degraded email is sent with empty AI section
    And the chart is included normally

  Scenario: Both chart and AI fail 3 times → fully degraded
    Given retry_count=2 and both renderings fail
    Then the email contains:
      | section          | content                                          |
      | text/plain       | full plain version                                |
      | text/html        | "[Chart unavailable] Pattern: bullish_reversal" + AI section empty |
    And the email is sent
    And PENDING_ALERT is deleted

  Scenario: AI agent calls multiple tools
    Given Bedrock returns tool_use blocks for get_quote_info, get_news_headlines
    When ai_analyst executes the loop
    Then the tools are invoked sequentially
    And each tool result is sent back to Bedrock
    And eventually the model returns END_TURN with valid JSON
    And the loop terminates within max_tool_iterations

  Scenario: AI agent exceeds tool iteration cap
    Given Bedrock keeps requesting more tools beyond the cap
    When the cap is reached
    Then the loop is terminated and a final request is sent without toolConfig
    And if the final output is still unusable, LLMException is raised

  Scenario: Tool returns empty / data unavailable
    Given get_news_headlines returns []
    When the AI sees the empty result
    Then it can either request another tool or finalize
    And the analysis honestly reports limited information

  Scenario: Recipients vuoti → skip with warning
    Given config.recipients = []
    When I call dispatch_alerts([event])
    Then no SES call is made
    And a warning log is emitted: "skipping alert: no recipients for instrument abc-123"
    And the event counts as skipped (not failed, not queued)

  Scenario: Per-recipient SES failure isolation
    Given config.recipients has 3 entries
    And SES rejects 1 of them
    Then 2 SES calls succeed, 1 fails (logged as InvalidRecipientException)
    And the failure does NOT enqueue a retry

  Scenario: Poller skips non-due items
    Given items with retry_at in the future
    When the poller runs
    Then those items are NOT picked up

  Scenario: Poller idempotency under double execution
    Given two invocations see the same due PENDING_ALERT snapshot
    When both run, each first claiming the item via UpdateItem with ConditionExpression="retry_count = :expected"
    Then only one claim succeeds and that invocation alone proceeds to chart/AI/send
    And exactly one email is sent and exactly one state transition happens
    And the losing invocation is a complete no-op (no email, no write)

  Scenario: Audit enabled persists ALERT after successful dispatch
    Given audit_enabled=true and dispatch succeeds
    Then an ALERT item is also written
    And it includes a flag enrichment="full" or "degraded_*"

  Scenario: Email size respects SES 10MB limit
    Given the chart is ~150 KB
    Then the multipart message stays well below 10 MB
```

### Reference algorithm

```
dispatch_alerts(events):
  summary = {"sent": 0, "failed": 0, "queued": 0, "skipped": 0}
  for event in events:
    config = get_config(event.instrument_id)
    if config.recipients is empty:
      summary.skipped += 1; continue
    try:
      chart_png = render_chart(event)            # may raise ChartRenderException
      ai_result = run_ai_analyst(event)          # may raise LLMException
      send_full_email(event, config.recipients, chart_png, ai_result)
      summary.sent += 1
      if audit_enabled: persist_alert_audit(event, ...)
    catch (ChartRenderException, LLMException, DependencyUnavailableException) e:
      enqueue_retry(event, last_error=e)
      summary.queued += 1
  return summary

retry_poller_handler(input):
  due = query_due_pending_alerts(now)
  for item in due:
    try:
      chart_png = render_chart(item.event)
      ai_result = run_ai_analyst(item.event)
      send_full_email(item.event, recipients, chart_png, ai_result)
      delete_pending_alert(item)
    catch (ChartRenderException, LLMException, DependencyUnavailableException) e:
      if item.retry_count + 1 < MAX_ATTEMPTS:
        bump_retry(item, error=e)
      else:
        degraded_chart = chart_png if successful else null
        degraded_ai    = ai_result if successful else null
        send_degraded_email(item.event, recipients, degraded_chart, degraded_ai)
        delete_pending_alert(item)
```

---

## 10. Block 7 — Scheduling & Orchestration

**Goal**: Define Lambda functions, EventBridge schedules, observability, IAM, and manual operations that wire the whole pipeline together.

### Lambda functions

| Function             | Trigger                                  | Memory | Timeout | Concurrency | Purpose                                |
|----------------------|------------------------------------------|--------|---------|-------------|----------------------------------------|
| `monitoring-main`    | EventBridge cron `0 22 * * ? *` (daily)  | 1024 MB| 900 s   | reserved=1  | ingest → HA → detect → dispatch         |
| `retry-poller`       | EventBridge cron `*/15 * * * ? *`        | 1024 MB| 300 s   | reserved=1  | process due `PENDING_ALERT` then `STRATEGY_PENDING_ALERT` items (SI-3c.3) |

Both:
- runtime `java25` managed
- SnapStart `PublishedVersions`
- arch `arm64`
- DLQ → SNS topic `monitoring-dlq`
- handler ARN: `<package>.MonitoringMainHandler::execute` and `<package>.RetryPollerHandler::execute`

### Daily timeline

```
22:00 UTC ─► monitoring-main ──► all active instruments processed sequentially
                                  ├── 1d closed bar ingested
                                  ├── 1w bar (if Friday) ingested
                                  ├── HA computed
                                  ├── pattern detected
                                  └── alerts dispatched (full or queued)

22:15, 22:30, ... ─► retry-poller (every 15 min)
                     └── recovers due PENDING_ALERT and STRATEGY_PENDING_ALERT items, retry or degrade

Sat-Sun: monitoring-main runs but is essentially a no-op (no new closed bars).
retry-poller runs unchanged.
```

### Domain operations (Gherkin)

```gherkin
Feature: Scheduling and Orchestration

  Scenario: Scheduled daily run, all instruments succeed
    Given EventBridge fires monitoring-main at 22:00 UTC
    And 10 active instruments exist
    When the handler runs
    Then all 10 are processed in sequence
    And the summary returned includes counts and duration
    And custom metrics are emitted to CloudWatch

  Scenario: One instrument fails
    Given 10 active instruments and yfinance returns invalid for instrument B
    When the handler runs
    Then 9 succeed, 1 fails
    And the failure is logged with code, instrument_id, trace_id
    And the Lambda completes successfully (does NOT raise)

  Scenario: Lambda timeout safeguard (soft timeout)
    Given handler approaches the 15min Lambda timeout
    When 13 minutes elapsed
    Then a "soft timeout" check stops queueing new instruments
    And the partial summary is returned
    And remaining instruments are picked up next run (idempotent)
    And LambdaSoftTimeoutWarning is logged

  Scenario: Manual one-shot invocation for a single instrument
    Given an operator invokes monitoring-main with {instrument_ids:["abc-123"]}
    Then only that instrument is processed

  Scenario: Only the latest detected bar fires an alert per (instrument, timeframe)
    Given an instrument with the color_change pattern enabled (min_streak_length=3)
    And the freshly computed HA chain contains pattern matches on bars t10, t50 and t100
    When monitoring-main runs
    Then only the events tied to bar_time = t100 are dispatched
    And a structured log "main_events_suppressed" reports the count of dropped events
    Note: protects against bootstrap "alert storms" (a 250-bar first ingest
    can otherwise emit dozens of stale historical alerts). Multiple distinct
    patterns matching the same latest bar (e.g. color_change + strong_candle)
    all fire — only chronologically older bars get dropped. Trade-off: a
    catch-up run after a Lambda outage loses patterns on intermediate bars;
    acceptable today, can be relaxed via a MainInput flag later.

  Scenario: Manual end-to-end pipeline smoke via force_email (pattern instrument)
    Given an operator invokes monitoring-main with {force_email:true}
    And the instrument is NOT monitored by a strategy
    And no real pattern fires for a tracked (instrument, timeframe)
    Then a synthetic PatternEvent(pattern=FORCED, subtype=FORCED) is built
      from the latest persisted HA + OHLC bar for that (instrument, timeframe)
    And chart + AI + email run end-to-end against that event via the legacy dispatch
    And when no HA bar exists yet, the synthesis is silently skipped with a WARN log
    Note: FORCED is a synthetic pattern kind, never produced by the detector
    and never settable via instrument config; it exists only to exercise the
    dispatch pipeline manually.

  Scenario: Manual end-to-end pipeline smoke via force_email (strategy instrument)
    Given an operator invokes monitoring-main with {force_email:true}
    And the instrument IS monitored by a strategy
    And the strategy does not fire on the latest bar this run
    Then a synthetic forced StrategyAlert is built from the latest persisted bar,
      carrying a single honest "forced" line (scenarioName="forced", role="forced",
      with no stop-loss / take-profit / position-precondition memo) — never a fake
      scenario match
    And it is routed through the strategy dispatch path
      (StrategyAlertDispatchService → strategy chart + AI + strategy email, with the
      strategy retry queue on transient failure), NEVER the legacy PatternEvent path
    And the strategy chart marks the bar with a neutral highlight labelled "forced"
      (the role-derived marker falls through to a bar highlight — no entry/exit glyph,
      because nothing actually fired)
    And when no persisted OHLC bar backs the trigger time, the synthesis is silently
      skipped with a WARN log (the chart marker sits on that bar, so without it the
      chart cannot render — mirroring the pattern path's OHLC guard)
    Note: a strategy instrument's manual smoke stays faithful to what a real strategy
    alert email looks like (strategy chart with scenario-derived overlays + strategy
    email body), instead of degrading to a generic pattern chart on the legacy path.
    The "forced" role is honest — it never asserts that a scenario matched, mirroring
    how FORCED is a synthetic pattern kind on the pattern path. The "no real event this
    run" guard already covers a genuine strategy alert (that path sets the per-timeframe
    real-event flag), so a forced strategy alert is only ever synthesised when the
    strategy stayed silent.

  Scenario: No active instruments → fast exit
    Given no instruments have status="active"
    Then the handler returns {processed:0} in <1s

  Scenario: Concurrency = 1 prevents overlap
    Given monitoring-main is currently running
    And EventBridge fires again
    Then the second invocation is rejected by reserved concurrency=1

  Scenario: Poller runs every 15 min
    Given EventBridge fires retry-poller every 15 min
    Then it queries GSI2 and processes each due item

  Scenario: Poller fails entirely (DynamoDB unreachable)
    Then the Lambda raises after retries exhausted
    And the failure goes to DLQ
    And alarm DLQDepth fires

  Scenario: Unhandled exception goes to DLQ
    Given handler raises an unhandled exception
    Then the event is delivered to SNS DLQ
    And alarm LambdaErrors fires within 5 min

  Scenario: Each invocation emits structured summary log
    When monitoring-main completes
    Then a single JSON log line includes:
      | field                  | example                     |
      | trace_id               | uuid                        |
      | run                    | "main"                      |
      | duration_ms            | 12345                       |
      | instruments_processed  | 10                          |
      | bars_inserted          | 17                          |
      | events_detected        | 3                           |
      | alerts_sent            | 3                           |
      | alerts_queued          | 0                           |
```

### Manual operations (CLI script)

| Operation                                | CLI example                                            |
|------------------------------------------|--------------------------------------------------------|
| Register instrument                      | `mon register --ticker AAPL --exchange NASDAQ`         |
| Update config                            | `mon config set --id <uuid> --policy ROLLING_WINDOW --window 200` |
| Update recipients                        | `mon config recipients --id <uuid> --add me@x.com`    |
| Update pattern                           | `mon config pattern --id <uuid> color_change.enabled=true color_change.min_streak_length=5` |
| List instruments                         | `mon list --status active`                             |
| Trigger manual run                       | `mon run --instruments <uuid1>,<uuid2>`                |
| Bulk recompute HA                        | `mon recompute-ha --id <uuid> --tf 1d`                 |
| Inspect pending retries                  | `mon retries list`                                     |
| Force flush retries                      | `mon retries flush --force-degraded`                   |

CLI uses AWS SDK v2 directly against DynamoDB / Lambda. No HTTP API.

---

## 11. Block 8 — Test Strategy

### Pyramid

| Level                | Volume target | Tools                                            | Scope                                              |
|----------------------|---------------|--------------------------------------------------|----------------------------------------------------|
| Unit                 | ~70%          | JUnit 5, AssertJ, Mockito, jqwik (PBT)           | pure functions: HA, detectors, validation, normalization, mappers |
| Integration          | ~25%          | JUnit 5, Testcontainers (LocalStack), AssertJ    | repositories, ingestion service, retry queue       |
| End-to-end           | ~5%           | JUnit 5, Testcontainers (LocalStack), scripted Bedrock fake | Lambda handler + pipeline simulation, snapshot email |

### Coverage gates (CI)

| Module                                                  | Line | Branch |
|---------------------------------------------------------|------|--------|
| `domain/**`                                             | 95%  | 90%    |
| `application/**`                                        | 90%  | 80%    |
| `infrastructure/**` (excluding EODHD/Marketaux/Yahoo-RSS/Bedrock adapters) | 80%  | 70%    |
| `orchestration/**` (handlers)                           | 75%  | 60%    |
| Global                                                  | 85%  | 75%    |

### Test scope per block (key items)

| Block | Key tests                                                                                       |
|-------|-------------------------------------------------------------------------------------------------|
| 0     | Round-trip serialize/deserialize each item type; key generation determinism; TTL math           |
| 0.B   | Each exception has expected `code`; repr does not leak secrets                                  |
| 1     | TransactWrite happy path + duplicate failure; validation cases; pagination; archive/restore preserves history; idempotent delete |
| 2     | Default config on registration; switch between policies removes/sets `rolling_window_size`; pattern param validation; recipient email validation |
| 3     | Bootstrap; steady-state; closed-bar filter (boundary `bar_time + period == now` excluded); idempotent re-ingestion; transient retry; circuit breaker; schema drift |
| 4     | **PBT**: HA invariants on any valid OHLC sequence; determinism on identical input; Decimal precision over 500-bar chains (drift < 10⁻⁸); retro OHLC change triggers chain recompute; SNAPSHOT_ONLY edge case |
| 5     | Each pattern positive + negative cases; NEUTRAL handling; insufficient history; multiple patterns same bar; single Query for history pre-fetch |
| 6     | Happy path; chart fail enqueues retry; AI fail enqueues retry; both fail enqueues retry; degraded email after 3 attempts; AI tool loop with mock; cap iterations; non-JSON output → `LLMException`; recipients empty skip; per-recipient SES failure isolation; MIME structure snapshot; HTML/plain snapshot |
| 7     | Handler happy path; partial failure; soft timeout; manual invocation with `instrument_ids`; no-active no-op; DLQ on unhandled; structured summary log |

### Mandatory PBT topics (jqwik)

| Module                  | Property                                                                  |
|-------------------------|---------------------------------------------------------------------------|
| `HeikinAshiCalculator`  | HA invariants hold on any valid OHLC sequence                             |
| `HeikinAshiCalculator`  | Determinism: identical input → identical BigDecimal output                |
| `HeikinAshiCalculator`  | No Decimal drift > 10⁻⁸ on 500-bar chains                                  |
| `ColorChangeDetector`   | No spurious event when streak < N or NEUTRAL in streak                    |
| `StrongCandleDetector`  | Threshold respect on randomized inputs                                    |
| `DojiDetector`          | Threshold respect; no event when `range == 0`                              |
| `TtlComputation`        | `compute_ttl > now` for any valid (bar_time, window, period)               |

### Mandatory idempotency tests

| Operation                                        | Test                                                          |
|--------------------------------------------------|---------------------------------------------------------------|
| `register_instrument`                            | TransactWrite fails on second registration                    |
| `ingest_timeframe`                               | second call with same input → 0 bars inserted                 |
| `compute_ha_for_timeframe`                       | two calls same input → identical output, byte-equal BigDecimals |
| `dispatch_alerts`                                | total Lambda retry after partial success → no duplicate emails |
| `retry_poller_handler`                           | concurrent execution on same `PENDING_ALERT` → ConditionalUpdate guard |

### Test determinism

| Rule                                                            |
|-----------------------------------------------------------------|
| No test reads real data over the network                        |
| No test uses `Instant.now()` or `UUID.randomUUID()` directly    |
| Fixtures (HTTP responses, Bedrock scripts, SES results) checked into the repo |
| Property overrides via `@MicronautTest(propertySources = ...)` or `@Property(name=..., value=...)` (NOT `@TestPropertySource`, which is Spring) |
| Parallel tests OK: no shared mutable state                      |

### AI agent loop testing

| Aspect                                              | Approach                                                          |
|-----------------------------------------------------|-------------------------------------------------------------------|
| Scripted mock of `BedrockRuntimeClient`             | sequence of pre-canned `ConverseResponse`s                         |
| Verify invocation count                             | `verify(mock, times(N))`                                          |
| Verify tool execution                               | tools mocked; assert args                                          |
| Cap test                                            | mock returns `TOOL_USE` 9× → expect cap behavior + `LLMException` if final unusable |
| Output parsing                                      | malformed JSON → `LLMException`; valid JSON wrong schema → `LLMException` |

### Email snapshot testing

| Aspect            | Approach                                                                     |
|-------------------|------------------------------------------------------------------------------|
| Subject           | string equality vs fixture                                                   |
| HTML body         | structural comparison (parse + assert), not byte-for-byte (CIDs vary)        |
| Plain text body   | string equality                                                              |
| MIME structure    | parse raw bytes, assert `multipart/related` containing `multipart/alternative` + `image/png` with `Content-Disposition: inline` |

### CI build gate

| Step              | Threshold                                |
|-------------------|------------------------------------------|
| Lint (Spotless)   | zero violations                          |
| Type check        | zero compilation warnings (with `-Werror`, except justified `unchecked`) |
| Unit              | zero failures                            |
| Integration       | zero failures                            |
| Coverage          | per table above                          |
| Mutation (PIT)    | optional, ≥ 60% on critical modules      |

---

## 12. Block 9 — Infrastructure as Code

### Decisions

| Aspect              | Choice                                              |
|---------------------|-----------------------------------------------------|
| Tool                | Terraform                                           |
| Environments        | single (`prod`)                                     |
| Apply               | GitHub Actions on push to `main` (OIDC)             |
| Deploy gate         | `mvn verify`, `terraform validate`, **and the Trivy `security-scan`** (a fixable HIGH/CRITICAL dependency vuln blocks the deploy chain via `aws-preflight-pre`'s `needs`) |
| State backend       | S3 (versioning + encryption) + DynamoDB lock        |
| Region (compute)    | `eu-central-1`                                      |
| Region (SES)        | `eu-central-1`                                      |
| Naming              | `monitoring-<resource>` (kebab-case)                |
| Mandatory tags      | `Project=monitoring`, `ManagedBy=terraform`, `Environment=prod` |

### Resource catalog

#### DynamoDB

| Resource              | Configuration                                                                |
|-----------------------|------------------------------------------------------------------------------|
| Table `monitoring`    | Billing `PAY_PER_REQUEST`. PK=`pk` (S), SK=`sk` (S). TTL on `ttl`. AWS-owned encryption. PITR enabled. |
| GSI `gsi_status`      | PK `gsi1Pk`, SK `gsi1Sk`. Projection `ALL`. Sparse.                           |
| GSI `gsi_retry_due`   | PK `gsi2Pk`, SK `gsi2Sk`. Projection `ALL`. Sparse.                           |

#### Lambda

| Resource                    | Configuration                                                          |
|-----------------------------|------------------------------------------------------------------------|
| Function `monitoring-main`  | Runtime `java25`. Memory 1024 MB. Timeout 900 s. SnapStart `PublishedVersions`. arm64. Reserved concurrency 1. DLQ → SNS. |
| Function `retry-poller`     | Same. Timeout 300 s.                                                   |
| Lambda artifact             | uploaded to S3 `monitoring-artifacts/<sha>/heikin-monitor-lambda.jar`  |
| Aliases                     | `live` pointing at latest published version                             |

#### EventBridge

| Resource                          | Configuration                                                       |
|-----------------------------------|---------------------------------------------------------------------|
| Rule `monitoring-daily`           | `cron(0 22 * * ? *)`. Target: `monitoring-main:live`.                |
| Rule `monitoring-retry-poller`    | `cron(0/15 * * * ? *)`. Target: `retry-poller:live`.                  |

#### SES

| Resource           | Configuration                                                          |
|--------------------|------------------------------------------------------------------------|
| Identity (email)   | sender from SSM. DKIM verification (DNS records emitted as Terraform output, applied manually if DNS not Terraform-managed). |
| Production access  | requested **manually** before first deploy (Terraform does not automate). |

#### SNS

| Resource                  | Configuration                                                  |
|---------------------------|----------------------------------------------------------------|
| Topic `monitoring-dlq`    | DLQ for both Lambdas. Optional email subscription for ops alerts. |
| Topic `monitoring-ops-alerts` | Target for CloudWatch alarms.                              |

#### CloudWatch

| Resource                          | Configuration                                                |
|-----------------------------------|--------------------------------------------------------------|
| Log group `/aws/lambda/monitoring-main`  | retention 30 days                                     |
| Log group `/aws/lambda/retry-poller`     | retention 30 days                                     |
| Custom namespace                  | `Monitoring/HeikinAshi` (created implicitly by code)         |
| Alarms                            | see Observability section below                              |

#### IAM (two roles)

| Role                       | Permissions                                                                |
|----------------------------|----------------------------------------------------------------------------|
| `monitoring-main-role`     | DynamoDB R/W (table + GSI1 + GSI2); Bedrock InvokeModel/Converse on model ARN; SES SendRawEmail/SendEmail on sender ARN; SSM GetParameter on `/monitoring/*`; KMS Decrypt; SNS Publish on DLQ; CloudWatch Logs/Metrics |
| `retry-poller-role`        | identical                                                                   |

#### SSM Parameter Store

All parameters under `/monitoring/*`. See Configuration Appendix for the full list. Non-secret values managed by Terraform; SecureString values populated **manually** or via CI secret-loading job.

#### Bedrock

Terraform does **not** manage model access. Request access manually via console for the chosen model in the chosen region.

#### S3

| Resource                     | Configuration                                                     |
|------------------------------|-------------------------------------------------------------------|
| Bucket `monitoring-artifacts`| versioning, AES256, public access blocked. Lifecycle: delete versions > 90 days. |
| Bucket `monitoring-tfstate`  | created by separate bootstrap (chicken-and-egg).                  |

#### Bootstrap (separate Terraform stack)

| Resource                                | Configuration                                                |
|-----------------------------------------|--------------------------------------------------------------|
| Bucket S3 `monitoring-tfstate`          | versioning, AES256, public access blocked                    |
| Table DynamoDB `monitoring-tflock`      | PK `LockID` (S), `PAY_PER_REQUEST`                            |
| OIDC identity provider for GitHub       | trust limited to `repo:<owner>/<repo>:ref:refs/heads/main`    |
| Role `gh-actions-monitoring-deploy`     | full permissions on project resources                         |

Apply once manually, version reference in repo.

### Terraform outputs

| Output                          | Consumer                            |
|---------------------------------|-------------------------------------|
| `lambda_main_arn`               | GitHub Actions (jar update)         |
| `lambda_retry_arn`              | GitHub Actions                      |
| `lambda_main_alias_live`        | EventBridge rule                    |
| `lambda_retry_alias_live`       | EventBridge rule                    |
| `dynamodb_table_name`           | Lambda env vars                      |
| `sns_dlq_arn`                   | Lambda env vars                      |
| `bedrock_model_arn`             | IAM policy                           |
| `ses_sender_arn`                | IAM policy                           |
| `artifacts_bucket_name`         | GitHub Actions                       |

### GitHub Actions workflow (high level)

| Job              | Steps                                                                                              |
|------------------|----------------------------------------------------------------------------------------------------|
| `test`           | checkout → setup JDK 25 → `mvn verify`                                                              |
| `build`          | `mvn package -DskipTests` → upload artifact                                                         |
| `terraform-plan` | (PR) checkout → setup terraform → `terraform init` → `terraform plan -out=plan.bin` → comment PR    |
| `terraform-apply`| (push main) `terraform init` → `terraform apply -auto-approve`                                      |
| `deploy-lambda`  | upload jar to S3 → `aws lambda update-function-code` for both functions → `publish-version` → `update-alias --name live --function-version <new>` |

Auth: OIDC federation, no static keys.

### Pre-deploy checklist

| #  | Step                                                                  |
|----|-----------------------------------------------------------------------|
| 1  | AWS account active                                                    |
| 2  | Apply Terraform "bootstrap" (state bucket, lock table, OIDC, role)   |
| 3  | Verify SES sender email                                                |
| 4  | Request SES production access (can take days)                          |
| 5  | Request Bedrock model access in chosen region                          |
| 6  | EODHD + Marketaux API keys obtained; pre-test the symbols you plan to use |
| 7  | Populate SSM SecureString: `ses/sender-email` (and any other secrets) |
| 8  | Configure GitHub repo: Settings → Secrets/Variables (region, account id) |
| 9  | First push to `main` → workflow applies Terraform + deploys Lambdas    |
| 10 | Test invocation: `aws lambda invoke --function-name monitoring-main --payload '{}' /dev/null` |
| 11 | Insert first instrument via CLI script                                 |
| 12 | Wait for cron 22:00 UTC or invoke manually                             |

---

## 13. Block 10 — Code Style & Testing Approach

### Guiding principles

| Principle                                          | Practical consequence                                                |
|----------------------------------------------------|----------------------------------------------------------------------|
| Immutability by default                            | `record` for domain types, `final` fields in services                |
| Pure functions for business logic                  | HA, pattern detection, validation → static methods or stateless classes |
| Hexagonal-ish (ports & adapters)                   | domain depends only on interfaces; implementations in `infrastructure/` |
| Fail fast at startup                               | Config + dependency validation at Micronaut context init             |
| Explicit > magical                                 | constructor injection over field; explicit `@Factory` over auto-discovery |

### Language conventions (Java 25)

| Topic                          | Convention                                                              |
|--------------------------------|-------------------------------------------------------------------------|
| Domain types                   | `record` immutable                                                       |
| Persistence beans (Enhanced DynamoDB) | mutable classes with `@DynamoDbBean`; **separate** from domain records; explicit mappers |
| Closed sets                    | `sealed interface` or `enum` (Timeframe, StoragePolicy, PatternKind, AlertSubtype). Never raw `String` |
| Pattern matching               | `switch` expressions with patterns (`case Foo f -> ...`) for sealed dispatch |
| `Optional`                     | use for "may not exist" in returns; **never** as parameter or field      |
| `null`                         | banned in domain layer; allowed only at the boundary with DynamoDB SDK and JSON parsing |
| Exceptions                     | all unchecked; hierarchy in `error/` (Block 0.B); domain never raises infrastructure exceptions |
| Money / prices                 | **`BigDecimal` everywhere**, never `double`/`float`; `MathContext.DECIMAL64` for HA; `RoundingMode.HALF_UP` for display |
| Time                           | `Instant` UTC end-to-end; **never** `LocalDateTime` without zone; `ZonedDateTime` only at display boundary |
| Identifiers                    | `UUID v4` via injectable `UuidGenerator`                                |

### Architectural layers

| Layer                       | Contents                                                                  | Allowed dependencies               |
|-----------------------------|---------------------------------------------------------------------------|------------------------------------|
| `domain`                    | records + sealed types + pure functions + port interfaces                 | nothing external                   |
| `application` (services)    | use case orchestration                                                    | `domain`                           |
| `infrastructure`            | DynamoDB / EODHD / Marketaux / Yahoo-RSS / Bedrock / SES / JFreeChart adapters | `domain`, `application`, AWS SDK |
| `orchestration` (Lambda)    | handlers + wiring                                                          | all                                |

Rule: **if an import in `domain/**` references `software.amazon.*` or `org.jfree.*`, that's an architectural bug.**

### Annotations — Micronaut, NOT Spring

These are easy to confuse. Always use the Micronaut/Jakarta package:

| Annotation                | Correct package (Micronaut/Jakarta)                          | Spring equivalent (DO NOT USE)              |
|---------------------------|--------------------------------------------------------------|---------------------------------------------|
| `@Singleton`              | `jakarta.inject.Singleton`                                   | `org.springframework.stereotype.Component`  |
| `@Inject`                 | `jakarta.inject.Inject`                                      | `org.springframework.beans.factory.annotation.Autowired` |
| `@Factory`                | `io.micronaut.context.annotation.Factory`                    | `@Configuration` + `@Bean`                  |
| `@ConfigurationProperties`| `io.micronaut.context.annotation.ConfigurationProperties`    | `org.springframework.boot.context.properties.ConfigurationProperties` (different!) |
| `@Value`                  | `io.micronaut.context.annotation.Value`                      | `org.springframework.beans.factory.annotation.Value` (different!) |
| Test class annotation     | `@io.micronaut.test.extensions.junit5.annotation.MicronautTest` | `@SpringBootTest`                       |
| Test property override    | `@Property(name="...", value="...")` from `io.micronaut.context.annotation.Property` | `@TestPropertySource` |
| Bean Validation           | `jakarta.validation.constraints.*`                           | same package (works in both, but Spring stack is different) |

**Anti-pattern: Spring-isms.** Never use `@SpringBootApplication`, `@RestController`, `@Service`, `@Repository`, `@TestPropertySource`, Spring's `@Value` or `@ConfigurationProperties`. The build will compile if Spring deps leak in, but the runtime will be broken or behave subtly wrong.

### Concurrency, state, SnapStart

| Constraint                                                     | Reason                                              |
|----------------------------------------------------------------|-----------------------------------------------------|
| `@Singleton` beans must not hold per-invocation mutable state  | SnapStart reuses the context across invocations     |
| SDK clients / connection pools created once, reused            | re-init cost is high                                 |
| No `static` mutable shared state                               | thread safety + SnapStart restore + parallel tests  |
| No `ThreadLocal` with invocation values                        | Lambda runtime does not guarantee thread association |
| MDC                                                             | populated at handler start, **`clear()`** in `finally` |
| Resources to avoid in init phase                                | network connections to ephemeral state, file descriptors representing transient data |

### Injection of non-pure resources

Everything non-deterministic goes behind an injectable interface:

| Resource                 | Interface                            | Default impl                   | Test mock              |
|--------------------------|--------------------------------------|--------------------------------|------------------------|
| Time                     | `java.time.Clock`                    | `Clock.systemUTC()`            | `Clock.fixed(...)`     |
| UUID                     | `UuidGenerator` (custom)             | random impl                    | sequenced impl         |
| External HTTP            | `MarketDataProvider`                 | `CompositeMarketDataProvider`  | in-memory fake         |
| DynamoDB                 | per-entity repositories              | `DynamoDb*Repository`          | LocalStack             |
| Bedrock                  | `AiAnalyst`                          | `BedrockAiAnalyst`             | scripted mock          |
| SES                      | `EmailSender`                        | `SesEmailSender`               | capturing mock         |
| Config                   | `@ConfigurationProperties` records   | from `application.yml` + SSM   | `@Property` overrides  |

Never call `Instant.now()`, `UUID.randomUUID()`, `new Random()` directly inside business logic.

### Configuration

| Convention                                                              |
|-------------------------------------------------------------------------|
| All config via `@ConfigurationProperties` with Bean Validation           |
| Sensible defaults in the config records                                  |
| Secrets via SSM SecureString; never in `application.yml` (only env override) |
| Config binding failure = startup failure (fail fast)                     |
| No runtime filesystem reads for config                                   |

### Logging

| Convention                                                              |
|-------------------------------------------------------------------------|
| JSON output (Logstash encoder configured in `logback.xml`)              |
| Default level `INFO`. `DEBUG` only for local troubleshooting.           |
| MDC populated at handler start: `trace_id`, `run`                       |
| MDC enriched in inner loops: `instrument_id`, `timeframe`, `bar_time`   |
| Never log: API key, full SSM SecureString, recipients in clear (mask `a***@example.com`), full Bedrock request payload |
| Errors always include `code=...`; no IOException stack traces at INFO   |
| One log entry per significant business decision: `pattern_detected`, `alert_sent`, `retry_enqueued`, `instrument_skipped` |

### Error handling

| Rule                                                                    |
|-------------------------------------------------------------------------|
| Domain layer raises only exceptions from `error/` (subclassing abstract `DomainException`) |
| Infrastructure boundary converts `SdkException`, `IOException`, etc. to appropriate domain exceptions |
| Never `catch (Exception e)` without categorization; use targeted superclasses (`TransientException`, etc.) |
| Never swallow exceptions: handle + log, or rethrow                      |
| `TransientException` → retry-eligible; `Validation/Conflict/NotFound` → no retry |

### Anti-patterns (explicit)

| Anti-pattern                                                    | Why it's wrong                                           |
|-----------------------------------------------------------------|----------------------------------------------------------|
| `double`/`float` for prices                                     | precision drift in long HA chains                         |
| `Instant.now()` in business logic                               | time-dependent test flakiness                             |
| `static` mutable cache                                          | thread safety + SnapStart + parallel tests                |
| `Thread.sleep` in tests                                         | flaky; use `awaitility` or fixed clock                    |
| Generic `RuntimeException` raises                               | impossible to classify retry vs no-retry                  |
| Repository returning DynamoDB SDK types to domain               | breaks separation                                         |
| `if (config.recipients == null)` in domain                      | recipients is non-null by config validation               |
| Tools (Bedrock) doing DB writes                                 | tools are read-only by contract                           |
| Sending email with SMTP credentials                             | must go through SES SDK with IAM                          |
| Logging API key, full sender, full Bedrock payload              | security + compliance                                     |
| catch + log + ignore                                            | hides bugs                                               |
| Hard-coded `Locale.getDefault()`                                | Lambda-vs-dev rendering divergence breaks snapshot tests  |
| Spring annotations (`@SpringBootApplication`, `@RestController`, `@Service`, `@TestPropertySource`, Spring's `@Value`/`@ConfigurationProperties`) | wrong stack; runtime broken or subtly wrong |
| `@TestPropertySource` (Spring)                                  | use `@Property` or `@MicronautTest(propertySources=...)` |

---

## Block 11 — ha-track dependency adoption

**Supersedes:** nothing. Foundational; prerequisite of Block 12–16.

**Goal**: Add the ha-track libraries as Maven dependencies at the same `${hatrack.version}` already used by wichtelm-app, and introduce a single adapter between H-tchen's domain bar types and ha-track's `commons` bar types — the one boundary every later block depends on.

**Naming caution (homonyms across packages).** ha-track and H-tchen share type names. Throughout Block 11–16:
- `org.hatrack.commons.*` = library types (target)
- `com.heikinashi.monitoring.domain.*` = current custom types (to be deprecated)

"the commons `HABar`" always means `org.hatrack.commons.HABar`, never the domain one.

**Prerequisites**
- Confirm by reading `org.hatrack.commons` (`OHLCBar`, `HABar`, `OHLCSeries`, `HASeries`): field names, BigDecimal scale, timestamp type, how `HASeries` is constructed.

```gherkin
Feature: Bar type adapter between domain and commons

  Scenario: Domain HABar list converts to a commons HASeries
    Given a list of com.heikinashi.monitoring.domain.HABar
    When converted
    Then an org.hatrack.commons.HASeries is produced
    And open/high/low/close are equal by BigDecimal compareTo
    And timestamp ordering is preserved

  Scenario: Scale mismatch is explicit, never silently rounded
    Given a domain bar whose BigDecimal scale differs from commons' expectation
    When converted
    Then scale handling is explicit
    And any precision change is asserted in a test, not implicit
```

**Operational invariants**
- The adapter is the ONLY place importing both `com.heikinashi.monitoring.domain` bar types and `org.hatrack.commons` bar types.

**Out of scope**
- Deleting domain bar types (kept until later blocks decide their fate).
- Any detection/HA/chart/ingest change (their own blocks).

---

## Block 12 — Heikin Ashi computation via commons

**Supersedes:** Block 4 (Heikin Ashi Computation).

**Goal**: Compute HA bars via `org.hatrack.commons.HeikinAshiCalculator` instead of the custom `com.heikinashi.monitoring.domain.HeikinAshiCalculator`, so HA formulas have one canonical implementation shared with wichtelm-app.

**Prerequisites**
- Block 11 adapter in place.
- Confirm the commons calculator's first-bar seeding matches the "Heikin Ashi formulas (canonical reference)" already documented in this CLAUDE.md (Data Model section).

```gherkin
Feature: HA computation delegates to commons

  Scenario: Commons calculator reproduces the canonical first-bar seed
    Given an OHLC chain whose first HA bar is defined by the CLAUDE.md reference
    When HA bars are computed via commons
    Then the first HA bar matches the reference by BigDecimal compareTo

  Scenario: Idempotent recompute
    Given HA bars already persisted for an instrument/timeframe
    When computation runs again on the same OHLC chain
    Then recomputed HA bars are identical and persistence is a no-op

  Scenario: Newly computed bars are reported downstream
    When computation completes
    Then the list of newly computed HA bars is returned for detection
```

**Operational invariants**
- The persisted HA attribute schema in DynamoDB is unchanged — this block changes the calculator, not the storage model.
- First-bar seeding must be equivalent to the current implementation, or the divergence must fail loud (see cascade note), not auto-reconcile.

**Out of scope**
- Changing the DynamoDB HA item layout.

**Cascade warning**: if the commons seed differs from H-tchen's, every persisted HA bar recomputes differently and alert history shifts. A parity test MUST fail loudly on divergence before merge.

---

## Block 13 — Quote ingestion via frau-holle-eodhd

**Supersedes:** Block 3 (Quote Ingestion via EODHD).

**Goal**: Fetch OHLC via `frau-holle-eodhd` behind the existing `MarketDataProvider` port, replacing the custom `EodhdMarketDataProvider`, so the EODHD integration is shared and maintained in one place.

**Prerequisites**
- Confirm frau-holle-eodhd's fetch API, API-key/config injection, timeframe vocabulary (daily/weekly), and its closed-bar guarantee — by reading the module.
- Confirm its returned bars convert via the Block 11 adapter (or a sibling OHLC adapter).

```gherkin
Feature: Ingestion uses frau-holle-eodhd

  Scenario: Ingestion still depends only on the MarketDataProvider port
    Then no orchestration code imports frau-holle-eodhd directly
    And the new provider is the only implementation behind the port

  Scenario: Only closed bars are ingested
    Given today's bar is not yet closed
    When ingestion runs
    Then the open bar is excluded

  Scenario: Idempotent persistence and storage policy preserved
    When the same closed bars are ingested twice
    Then DynamoDB writes are idempotent
    And the per-instrument storage policy and TTL are applied as before
```

**Operational invariants**
- The `MarketDataProvider` port signature stays stable; only its implementation swaps.
- EODHD API-key handling must match the current secret-injection mechanism — confirm against the existing `EodhdConfig`, do not assume env-var vs SSM.

**Out of scope**
- Adding frau-holle-csv (not used; flag if offline testing is wanted later).

---

## Block 14 — Chart rendering via heerwisch

**Supersedes:** the chart-rendering half of Block 6 (Rich Alert Dispatch). The AI note (Bedrock), SES send, and retry queue in Block 6 are untouched.

**Goal**: Render the HA alert chart via heerwisch's `ChartRenderer`/`ChartSpec` instead of the custom `JFreeChartRenderer`, so chart output is shared with wichtelm-app.

**Prerequisites**
- Confirm heerwisch-api `ChartSpec` / `ChartRenderer` and the heerwisch-jfreechart driver output vs H-tchen's `ChartImage` — by reading the modules.
- Confirm the email composer can consume what heerwisch produces.

```gherkin
Feature: Chart rendering via heerwisch

  Scenario: An alert chart renders from a ChartSpec
    Given an alert and its HA series window
    When the chart is rendered
    Then heerwisch produces an image consumable by the existing email composer
    And the triggering candle is highlighted

  Scenario: Headless rendering unchanged
    When rendering runs in the Lambda environment
    Then no display or server is required
```

**Operational invariants**
- The email MIME structure (multipart text+HTML, inline image CID) stays intact — only the image producer changes.

**Out of scope**
- The AI note, SES send, retry/fallback queue (remain custom, untouched).

---

## Block 15 — Detection via nachtkrapp; strategies replace fixed patterns

**Supersedes:** Block 5 (Heikin Ashi Pattern Detection) — both the detection engine and the three-fixed-pattern model.

**Goal**: Replace the custom `PatternDetector` with nachtkrapp's `org.hatrack.nachtkrapp.detector.PatternDetector`, and move from three hard-coded patterns to per-instrument strategies whose conditions are nachtkrapp `DetectionRule`s. A strategy supersedes the fixed patterns for an instrument: an instrument monitored by a strategy no longer emits the three legacy alerts. Anyone wanting the old behaviour expresses it as a minimal one-condition strategy.

**Prerequisites**
- Block 11 adapter in place.
- Strategies are supplied to H-tchen as an imported JSON file in an H-tchen-owned format (Block 16 defines monitoring semantics; the JSON schema itself is an H-tchen artifact). The JSON is translated, inside H-tchen, into `DetectionRule`s. nachtkrapp does NOT serialize `DetectionRule` (JDK-only records, by design) — the translation is H-tchen code, in one place.

```gherkin
Feature: Detection delegates to nachtkrapp, driven by strategies

  Background:
    Given an instrument configured with an imported strategy
    And the strategy's market conditions translated into DetectionRules

  Scenario: A strategy condition maps to a nachtkrapp rule and is evaluated
    When detection runs
    Then a DetectionSpec is built via DetectionSpecBuilder
      # withSeries, withTimeframe, addRule
    And org.hatrack.nachtkrapp.detector.PatternDetector.detect is invoked

  Scenario: Strategy supersedes the three fixed patterns
    Given an instrument monitored by a strategy
    When detection runs
    Then no legacy color_change / strong_candle / doji alert is produced for it
    And only the strategy's scenarios can raise alerts

  Scenario: Only the latest bar can raise an alert
    Given a long first ingest
    When detection runs
    Then at most the latest bar is evaluated for alerting

  Scenario: Detection remains a pure function
    When detection runs
    Then no I/O occurs except reading historical HA bars

  Scenario: A condition with no DetectionRule equivalent fails loud at import
    Given an imported strategy JSON naming a condition with no nachtkrapp DetectionRule
    When the strategy is imported
    Then import fails loud, naming the unsupported condition
    And no partial monitoring of that strategy is established
```

**Operational invariants**
- The translation JSON -> `DetectionRule` is the single point that rejects unsupported conditions (fail loud, never partial).
- Determinism: same HA series + same strategy => same alerts (BigDecimal arithmetic preserved by nachtkrapp).

**Out of scope**
- The strategy-monitoring behaviour (role labels, memo, transition, multi-match): Block 16.
- The JSON schema field list (an H-tchen design artifact, produced during implementation).

---

## Block 16 — Strategy monitoring behaviour (stateless)

**Supersedes:** nothing (new behaviour layered on Block 15).

**Goal**: Given an imported strategy, evaluate its market-condition scenarios against the latest bar and email which scenario matched — labelled by role, with stop-loss/take-profit quoted verbatim as a memo — while position state and order placement remain the user's. H-tchen is stateless: it never tracks whether a position is open.

**Conceptual model (from the .strat shape the exporter maps from).** Each scenario carries:
- market conditions H-tchen evaluates;
- a role label (entry/exit, long/short) — a free-text string, used for display only, never branched on;
- an optional stop-loss and take-profit expression — verbatim text, never evaluated;
- a position precondition (e.g. "a long position is open") — shown as context, never evaluated.

```gherkin
Feature: Monitor an imported strategy, stateless

  Background:
    Given an instrument with an imported strategy
    And each scenario carries market conditions, a role label, and optional memo text

  Scenario: A market-condition scenario matches on the latest bar
    Given a scenario whose conditions are all market conditions
    When every condition is true on the latest bar
    And the scenario was not already true on the previous bar
    Then one alert line is produced
    And it shows the scenario's role label verbatim
    And it includes the scenario name for traceability

  Scenario: An exit-signal scenario is a first-class alert
    Given a scenario whose role is an exit and whose conditions are market conditions
    When those conditions are true on the latest bar
    Then an alert labelled as that exit role is produced
    And it is treated exactly like an entry alert, not demoted to memo

  Scenario: Stop-loss and take-profit are quoted, never evaluated
    Given a matched entry scenario with stop-loss and take-profit text
    Then the alert quotes both verbatim, as written in the source (e.g. entry * 0.98)
    And no absolute price is computed from them
    And the memo states these are for the user to configure at their broker

  Scenario: Position precondition is context, not evaluated
    Given a matched exit scenario whose precondition is "a long position is open"
    Then H-tchen does not check whether a position is open
    And the alert notes the scenario applies only if the user is long

  Scenario: Per-scenario transition gating
    Given a scenario stays true across consecutive bars
    When evaluation runs each day
    Then an alert fires only on the bar where that scenario first became true
    And each scenario's true/false memory is tracked independently

  Scenario: Multiple scenarios match on the same bar
    Given two scenarios of one strategy match on the same latest bar
    Then a single email is sent with one line per matched scenario

  Scenario: Insufficient history for a cross-based condition
    Given a condition needing the previous bar
    And fewer bars than required are available
    When evaluation runs
    Then nachtkrapp's InsufficientDataException is handled
    And no alert is produced for that scenario

  # SI-3 orchestration — the run routes a strategy alert to the dedicated dispatch
  Scenario: A strategy instrument dispatches a strategy alert in a run
    Given an instrument monitored by a strategy whose scenario matches on the latest bar
    When monitoring-main runs
    Then the strategy alert is dispatched via the dedicated strategy dispatch (chart + AI + email)
    And no legacy fixed-pattern alert is produced for it
    And the run counts it among alerts sent

  # SI-3 read-consistency — strategy evaluation must see the bar the run just wrote.
  # DynamoDB findLastN is an eventually consistent Query; read-after-write
  # immediately after ingestion is exactly where it can lag and omit the
  # just-written bar, silently evaluating (and possibly re-alerting) the PREVIOUS
  # bar — and the missed bar is never revisited. The freshly ingested OHLC bars
  # are therefore merged into the persisted series before evaluation (the run
  # already holds them in memory; no consistent-read cost).
  Scenario: Strategy evaluation sees the freshly ingested bar even when the persisted read lags
    Given an instrument monitored by a strategy whose scenario matches on the latest bar
    And the persisted OHLC read lags behind the just-ingested bar
    When monitoring-main runs
    Then the strategy is evaluated against the just-ingested latest bar, not the stale tail
    And the strategy alert carries the just-ingested bar's time
    And the chart window handed to the strategy renderer contains the trigger bar
    Note: the merge applies to BOTH reads — evaluation (else the alert is missed)
    and the chart lookback (else the marker bar is absent from the series, the
    render fails V7 and a correctly detected alert degrades onto the retry queue
    with no trigger-bar snapshot, instead of a first-attempt email). The merged
    series stays capped at STRATEGY_LOOKBACK_BARS (trimmed from the oldest side):
    an outage catch-up that inserts many bars must not hand long indicators a
    larger warmup window than the documented 300-bar cap.

  # SI-3 failure surfacing — strategy dispatch must fail the run exactly like the
  # legacy path. Legacy dispatch runs AFTER the per-instrument try/catch, so an
  # audit-write failure (email already accepted) or a retry-enqueue PutItem
  # failure propagates out of the handler → the invocation errors → Lambda
  # retry/DLQ. Strategy dispatch (real and forced alerts alike) follows the same
  # placement: detection/chart-read stay inside the guarded ingest/detect block;
  # dispatch happens outside it. An alert is never silently neither-delivered-
  # nor-queued.
  Scenario: A strategy audit-write failure after a delivered email fails the run like legacy
    Given audit is enabled
    And an instrument monitored by a strategy whose scenario matches on the latest bar
    And the audit write will fail
    When monitoring-main runs
    Then the strategy email is still sent
    And the run fails with the audit error (it is not swallowed as an instrument failure)

  Scenario: A strategy retry-enqueue failure fails the run like legacy
    Given an instrument monitored by a strategy whose scenario matches on the latest bar
    And the chart renderer will fail transiently
    And the strategy pending-alert write will fail
    When monitoring-main runs
    Then the run fails with the persistence error (the alert is not silently lost)
```

**Operational invariants**
- H-tchen never evaluates stop-loss, take-profit, or position preconditions — only market conditions.
- Transition memory is per scenario, not per strategy.
- New alert wire values introduced for strategy scenarios are additive and stable (persisted in ALERT audit items).

**Out of scope**
- Evaluating any entry_price-relative expression (always memo; broker executes).
- Tracking position state (flat by design).
- Stateless-parametric evaluation via a user-supplied entry price and a future mail "commit" button (deferred; not on the critical path — see "Deferred / known issues", item 3).
- Order execution, paper-trading, P&L.
- Verifying the imported strategy matches the one backtested in wichtelm (no bridge between the tools — separate concern).

---

## Block 17 — EODHD news provider + AI-side relevance triage

**Supersedes:** nothing (third `NewsProvider` layered on the Block 3 aggregation; refined Three Amigos pass, 2026-06-12. EODHD All World Extended plan verified live: the News API responds; the sample showed loose multi-ticker tagging and advertorial noise — the design decisions below follow from that evidence).

**Goal**: As the operator of H-tchen-Mail, I want EODHD added as a third news provider with analyst-side relevance triage, so that alert notes can feature stories the legacy pair missed (observable per email via `data_sources` reporting `eodhd(N)`) and advertorial items never appear in a note.

**Verification mode**: no formal post-deploy acceptance check. Real-world triage quality will be assessed in a future follow-up session with actual alert emails attached. CI-verifiable parts are the Gherkin scenarios and the prompt-contract pin below.

### Dependencies

- `NewsProvider` port and `NewsAggregator` fan-out/dedup/cap (existing — the new provider plugs in beside `marketaux` and `yahoo-rss` in `monitoring.news.providers`).
- EODHD symbol mapping already used by the OHLC adapter: the news adapter MUST reuse `monitoring.exchanges.suffix-map` (the EODHD codes — `.US`, `.MI`, `.LSE`, ...), NOT `monitoring.exchanges.news-suffix-map` (the common market codes used by Marketaux/Yahoo). No second EODHD mapping may be introduced.
- EODHD API token already provisioned (same credential as OHLC ingestion — `monitoring.eodhd.api-key` via `MONITORING_EODHD_API_KEY`).
- Existing `BedrockAiAnalyst` SYSTEM_PROMPT (extended, not rewritten).
- The recency-window derivation already used for Marketaux (`tf` → published-after window): EODHD reuses the SAME derivation (same look-back values, same UTC-date computation), not a parallel one.

### Out of scope

- Carrying EODHD `sentiment` scores into the domain (`NewsHeadline` has no field for it; adding one touches domain + email + tool serialization).
- Replacing or disabling Marketaux / Yahoo RSS.
- Any client-side relevance heuristic (title match, symbols cap, position rules) — explicitly rejected after sample evaluation. **(Reversed in Block 18 on 15-email evidence — the symbols cap is adopted there as an entity-cardinality filter; title *match* remains rejected.)**
- A dedicated pre-filter LLM call.
- Changing TOP_N or the aggregator dedup rules. **(TOP_N's role changed in Block 18: it becomes a post-filter cap over an over-fetched candidate pool. Dedup rules remain unchanged.)**
- Cross-provider dedup scenario: redundant with existing URL-dedup coverage.

### Endpoint

`GET https://eodhd.com/api/news?s={SYMBOL}&from={YYYY-MM-DD}&to={YYYY-MM-DD}&limit={N}&api_token=...` — `s`, `limit`, `offset` and the response shape verified live; `from`/`to` confirmed against the official EODHD News API docs (date filtering, `YYYY-MM-DD`). Response items carry `date`, `title`, `content`, `link`, `symbols`, `tags`, `sentiment`.

### Data mapping (EODHD item → `NewsHeadline`)

| NewsHeadline field | EODHD field | Rule |
|---|---|---|
| title | `title` | verbatim |
| publishedAt | `date` | ISO 8601 with offset → `Instant` UTC |
| source | — (absent in payload) | host of `link`; empty string on malformed link, never a failure |
| url | `link` | verbatim |
| summary | `content` | truncated to `monitoring.eodhd.news.summary-max-chars` (default 600), cut at the last word boundary, ellipsis appended when truncated; hard cut when no space exists within the limit; empty string when `content` is absent |

The `tf` parameter scopes recency exactly as Marketaux does (EODHD `from`/`to` date filters); same window derivation, no new logic.

### Behaviour (Gherkin — `features/news/news_aggregation.feature` additions)

```gherkin
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
```

### Prompt extension (SYSTEM_PROMPT, new RELEVANCE block before CORROBORATING)

Add a RELEVANCE instruction stating, in substance:

- Treat fetched news items as *candidates*, not facts about the instrument: multi-ticker tagging is loose, and promotional/advertorial items (stock picks, "act now", newsletter upsells) may carry the ticker only as a passing mention.
- Discard from consideration any item that is promotional in nature or where the instrument is incidental to the story; never feature such an item in CORROBORATING or CONTRADICTING.
- If, after triage, no fetched item is genuinely about the instrument, say so explicitly (the existing "say so rather than padding" rule already covers the wording).

Wording is delegated; the three behaviours above are the contract. The existing CONFIDENCE instruction is untouched — discarded items must not count as evidence.

**Prompt-contract verification**: the relevance triage lives in the prompt, so it is verified at the snapshot level, not behaviourally — `BedrockAiAnalystTest` pins the presence of the RELEVANCE block in the system prompt sent to Bedrock, so a prompt regression fails CI. No attempt to unit-test the model's judgment; its real-world quality is assessed in the future follow-up session (Verification mode above).

### Config and constraints

- New config key `monitoring.eodhd.news.summary-max-chars` (default 600). Endpoint/token/timeout reuse the existing `monitoring.eodhd` section (same credential and base URL as OHLC ingestion).
- One new provider id `eodhd` accepted by `monitoring.news.providers`; enabled by default in `application.yml` (the goal requires `eodhd(N)` observable in real alert emails).
- New Cucumber steps only where the existing news-aggregation step vocabulary cannot express the truncation / source / recency-window assertions.
- No changes to `NewsHeadline`, `NewsAggregator` ordering/dedup, TOP_N, or any dispatch/retry path. If implementation pressure suggests otherwise, stop and report.

**Queued decisions (deliberately NOT in this increment)**:
1. EODHD `sentiment` in the domain (needs a new `NewsHeadline` field + email rendering + tool serialization).
2. Empirical validation of EODHD sentiment before using it (the 3 live samples had polarity 0.91–0.99 even on advertorials).
3. Separate tickets already agreed: spec-maintenance D1–D4, dedup post-send G4, AI cases (multi-tool, tool-empty).
4. Triage-quality follow-up with real alert emails attached (future session).

---

## Block 18 — Confidence inputs: chart-series context + news candidate pool

**Supersedes:** Block 17's `Out of scope` bullets "Any client-side relevance
heuristic (title match, symbols cap, position rules) — explicitly rejected after
sample evaluation" and "Changing TOP_N or the aggregator dedup rules". Both are
**reversed here on new evidence**: Block 17 rejected client-side filtering after
evaluating 3 live EODHD samples, on the reasoning that AI-side triage would
suffice. A 15-email real-alert study (below) shows AI-side triage cannot suffice,
because the `TOP_N` cap is applied *before* any relevance assessment and
routinely spends the entire candidate budget on incidentally-tagged items — the
model triages them away correctly and is then left with nothing. This block also
**closes Block 17's deferred `Verification mode`** ("real-world triage quality
will be assessed in a future follow-up session with actual alert emails
attached") and its queued decision 4.

**Goal**: As the recipient of HA alert emails, I want the AI analyst to receive
the same price/indicator series the alert chart already draws, and a news
candidate pool that is filtered for relevance before it is capped, so that
`FUNDAMENTAL CONFIDENCE` reflects the evidence actually available for the signal
instead of being pinned at LOW by a starved input pipeline.

### Evidence (15 real alert emails, 2026-07-20 → 2026-07-31)

Sample: `AMS.BME` ×5, `CFR.SWX` ×5 (4×`1d`, 1×`1w`), `NVDA.NASDAQ` ×4,
`GAW.LSE` ×1. Grades: **14 × LOW, 1 × MEDIUM**. Every email reported
`news_headlines(5)` with all five fundamentals tools at `(0)` — those five are
unimplemented by design (§ "News & fundamentals provider composition"), which is
**not** addressed in this block (see Out of scope).

The one MEDIUM is a controlled comparison: same instrument, same pipeline, same
five empty fundamentals tools. The only variable that changed was one news item
— the Amadeus H1 2026 earnings-call summary published on the pattern-bar date
itself, which the note used to identify a constant-currency-versus-reported
divergence consistent with the bearish reversal. Grade moved on news quality
alone.

The 14 LOWs isolate three distinct failure modes, and the observed rule is a
conjunction — an item must be recent **and** company-specific **and** materially
financial to lift the grade:

| Observed case | Recent | Company-specific | Material | Grade |
|---|---|---|---|---|
| Amadeus H1 earnings, same day as bar | yes | yes | yes | MEDIUM |
| GreensKeeper fund letter, same day as bar | yes | no | no | LOW |
| Amadeus–Spire product news, ~2 months old | no | yes | yes | LOW |
| Five other companies' Q2 earnings digests carrying `NVDA` as a tag | yes | no | no | LOW |

Two mechanical causes, both verified in code:

1. **The candidate pool is capped before it is filtered.** `TOP_N = 5`
   (`ToolCatalog`) is passed as `max` to *every* provider and reused as the
   aggregator's final truncation after a pure `publishedAt` sort
   (`NewsAggregator.fetchNewsHeadlines`). Relevance is never assessed
   server-side; the only triage anywhere is the model's, applied to whichever 5
   survived the recency sort. Additionally `YahooFinanceRssNewsProvider`
   documents that the RSS feed has no date-range filter and nothing filters its
   items afterwards, so items months older than the recency window
   (`recencyDays1d = 7`, `recencyDays1w = 30`) enter and can fill the cap.
2. **The analyst never receives the chart's data.** `BedrackAiAnalyst`'s
   `buildUserMessage(PatternEvent)` sends 4 HA values, 4 OHLC values, instrument,
   timeframe, `bar_time` and pattern name — and nothing else. The
   `buildUserMessage(StrategyAlert)` variant sends **no bar values at all**, only
   the strategy name and matched scenario names/roles. The rendered chart (30-bar
   lookback, moving averages, RSI sub-pane) is produced separately for the email's
   inline CID and never reaches the model. Consequence visible in the sample: the
   notes reason well from exact figures (correctly reading a no-upper-shadow HA
   candle as momentum confirmation, and a close at the exact intraday low as
   rejection) but **hedge on context the chart already shows** — "on a stock that
   had *presumably* been trading near the $195–$200 band", "capped near the $200
   level, which was *likely* a psychologically significant round-number
   resistance zone". No note in the 15 cites an RSI value, though RSI is plotted
   in all 15.

### Dependencies

- `HeerwischChartRenderer` / `ChartConfig` (Block 14, Component 1) — the
  pattern-alert chart's resolved indicator set.
- `StrategyChartIndicators.derive(Strategy)` (Component 1b) — the strategy-alert
  chart's resolved indicator set.
- `BedrockAiAnalyst` SYSTEM_PROMPT and both `buildUserMessage` overloads
  (extended, not rewritten).
- `NewsAggregator` fan-out/dedup ordering and the per-provider `max` contract.
- The existing `RecencyWindowSource` derivation (`recencyDays1d` / `recencyDays1w`).

### Out of scope

Deliberately excluded so this block stays two levers wide:

- **Implementing the five empty fundamentals tools** (`get_quote_info`,
  `get_earnings_calendar`, `get_recommendations`, `get_financials_summary`,
  `get_insider_transactions`). Still the largest single data gap, and still
  unaddressed; it is a separate increment with its own vendor-coverage question.
- Sending the chart **image** to Bedrock as a multimodal content block. This
  block sends the series numerically instead (rationale below). Revisit only
  after measuring the numeric path.
- Any empirical/base-rate recalibration of the confidence scale, any
  historical-outcome joining, and any change to what `confidence` *means*.
- A per-alert identifier (the `id` row renders `instrumentId`, so it is constant
  across bars and timeframes for an instrument), instrumented `data_sources`
  counts (today the field is model-authored prose and drifts — one email in the
  sample emitted `get_`-prefixed names and the ordering varies), and same-bar
  alert de-duplication (two NVDA emails cover the identical bar). All observed,
  all recorded here, none fixed here.
- `AlertEnrichment` semantics (`full` means chart+AI attached, not data coverage).
- Any change to `NewsHeadline`, provider enablement, dedup rules, dispatch or
  retry paths.

### Part A — chart-series context in the AI user message

**Principle: one source of truth.** The analyst receives *exactly* the series the
chart for this alert draws — same lookback window, same resolved indicator list,
same periods. It is never a hardcoded indicator set. This guarantees the note can
never cite an indicator the reader cannot see, nor miss one they can.

The indicator set is **dynamic** and differs per alert flow:

| Alert flow | Resolved indicator set comes from | Set is driven by |
|---|---|---|
| `PatternEvent` | `HeerwischChartRenderer.addIndicators` | `monitoring.chart` config: `SMA` iff `sma-period > 0` (default 10), `EMA` iff `ema-period > 0` (default 20), `RSI(rsi-period, 70, 30)` iff `show-rsi` (default true, period 14), volume iff `show-volume` (default false) |
| `StrategyAlert` | `StrategyChartIndicators.derive(strategy)` | the strategy's referenced indicators — `rsi`, `sma`, `ema`, `atr`, `stddev`, `macd_*`, `highest_high`/`lowest_low`/`highest_close`/`lowest_close` (RollingMax/RollingMin), plus the Tier-B `rsi_*` primitives threaded into a single RSI with their thresholds |

Required behaviour:

- **Extract the resolution, do not duplicate it.** The indicator list used to
  build the `ChartSpec` and the list serialized into the user message MUST be the
  same resolved value, obtained from the same call. A second, parallel derivation
  in the prompt builder is a spec violation.
- **Honour the chart's placement rules — all of them.** An indicator is described
  only if the chart actually draws it, and *three* rules decide that, not one.
  All three are the renderers' own:
  1. an indicator whose minimum window exceeds the available bars is skipped
     (heerwisch V6) — the comparison is strictly-less, so `bars == minBars` draws;
  2. duplicates collapse by indicator identity;
  3. non-`MAIN` indicators are placed into a **fixed set of eight subplot slots**,
     so a ninth oscillator onward is silently not drawn.

  Rule 3 was missed in the first implementation (Codex P2): a strategy referencing
  more than eight distinct oscillators (several RSI / MACD / ATR / StdDev periods)
  would have the note describing sub-panes absent from the image. Because the same
  loop was already duplicated between `HeerwischChartRenderer.addIndicators` and
  `StrategyChartSpec.placeIndicators`, the rules live in **one** shared helper —
  `ChartIndicatorPlacement.drawn(indicators, bars)`, returning the drawn indicators
  *with their assigned panes*, in draw order — consumed by both renderers and by the
  context builder. A fourth re-implementation is a spec violation; adding a subplot
  slot must change the drawn set and the described set together, by construction.
- **Window** = the same `monitoring.chart.lookback-bars` (default 30) window as
  the chart, loaded the same way (`HaRepository.findLastNBefore(instrumentId, tf,
  barTime+1ns, lookbackBars)`), ending at the pattern/alert bar. (Pattern flow;
  the strategy flow does not load its own window at all — see below.)
- **Per-bar series — per flow, because the two charts are not the same chart.**
  This was wrong in the first implementation and is corrected here (found by a
  Codex review of PR #86, P1):

  | Flow | Chart draws | Context series | Indicator basis |
  |---|---|---|---|
  | `PatternEvent` | HA candles from `HaRepository` | `HABar` window: bar time, `ha_open`/`ha_high`/`ha_low`/`ha_close`, HA colour | `ha_close` |
  | `StrategyAlert` | **raw OHLC** from `OhlcRepository` (`StrategyChartSpec` builds `toCommonsOhlcSeries`, and `StrategyChartIndicators` uses `PriceSource.CLOSE`) | `OHLCBar` window: bar time, `open`/`high`/`low`/`close` | raw `close` |

  Using the HA series for a strategy alert would print values disagreeing with
  both the attached strategy chart *and* the DSL condition that fired the alert —
  the exact failure this block exists to prevent. The basis follows the chart, so
  it differs per flow rather than being uniform.

  Raw OHLC for the **alert bar** in the pattern flow is unaffected — already in
  the message via `event.barSnapshot()`, which is what let a note in the sample
  correctly compare the raw close against the HA close.

- **Missing triggering bar (pattern flow).** Under `SNAPSHOT_ONLY` retention a
  retried alert's bar may already be deleted. The window resolution repairs that from
  `event.barSnapshot()` so the chart still contains the triggering candle, and the
  context sees the identical repaired list — see the invariant below, which supersedes
  the earlier "shared helper" formulation. Sharing a helper shared the *algorithm*, not
  the *data*: two calls to it could still return different windows (Codex P2).

- **THE INVARIANT, stated once and applied to both flows.** *Whatever the note describes
  about a rendered artefact is passed in; nothing about it is looked up.* Five review
  rounds all reduced to one cause — this rule being rediscovered per case (the strategy
  instance, then the strategy window, then the pattern window) instead of established
  once. It is therefore enforced structurally: **`TechnicalContextBuilder` holds no
  repository at all.** It cannot re-read, so it cannot disagree.

  Concretely, for both flows the *caller* resolves the bar window exactly once and hands
  the same list to the renderer and to the analyst:

  | Flow | Who resolves the window | Passed to |
  |---|---|---|
  | `PatternEvent` | `AlertDispatchService` / `RetryPollerService`, via `HaLookbackWindow.forEvent` | `ChartRenderer.renderChart(event, bars)` **and** `AiAnalyst.analyze(event, bars)` |
  | `StrategyAlert` | `MonitoringRunService` (fresh-bar merge) / `StrategyRetryPollerService` (trigger-bar splice) | `StrategyChartRenderer.render(alert, strategy, bars)` **and** `AiAnalyst.analyze(alert, strategy, bars)` |

  **Layering, which this must not break** (no `application` class imports
  `infrastructure`, and that holds today — verified, not assumed):
  - `HaLookbackWindow` moves from `infrastructure.chart` to `domain`. It only ever
    touched domain types (`HABar`, `HaRepository`, `PatternEvent`); it was in
    infrastructure only because it was extracted from the renderer.
  - the window size stays operator-configurable without leaking `ChartConfig` upward: a
    `domain.ChartWindowPolicy { int lookbackBars(); }` abstraction, which `ChartConfig`
    implements. This follows the `RecencyWindowSource` precedent (interface named where
    it is consumed, config class as the single implementation) rather than hardcoding a
    second copy of the default, which would silently diverge from `monitoring.chart`.

- **Strategy flow: the caller passes the bar window; the analyst MUST NOT re-query
  it.** A fresh repository read is *not* equivalent to the list the chart was drawn
  from, because two repairs are applied upstream and neither is reproducible by
  reading again (Codex P2):
  - `MonitoringRunService` merges the freshly-ingested bars over the repository read
    (`PatternDetectionService.mergedByBarTime`) because the read can lag its own
    write. Under a lagging read, **only** the merged list contains the trigger bar —
    which is precisely why the merge exists on the chart path.
  - `StrategyRetryPollerService.withTriggerBar` splices the queued snapshot back in
    when retention evicted the trigger bar before the retry ran.

  The bar window therefore travels as a parameter on the `AiAnalyst` strategy
  overloads, alongside the `Strategy`, for exactly the reason the `Strategy` does:
  the note must describe the artefact the reader was actually sent, and re-deriving
  either one re-opens the divergence instead of narrowing it.
  `TechnicalContextBuilder` consequently holds **no** `OhlcRepository` — for the
  strategy flow it is a pure function of what it is handed. A pleasant consequence:
  the window feeding the indicators is the same `bars.size()` the chart's skip rule
  was evaluated against, so rule 1 above cannot disagree between the two either.

  Indicator values are computed over the **whole** passed window (so long-period
  indicators are warm — the chart path loads 300 bars for this reason), while only
  the trailing `lookback-bars` rows are **displayed**, and the block states how many
  bars it is showing.

  Note what is and is not *testable* here: with the repository removed from the
  class, "does not re-read" is not an observable behaviour but a structural property
  — there is no collaborator left to query. The AAA test therefore asserts the
  positive (the passed window is the described window) rather than a negative it
  cannot see, and the structure is what prevents the regression.
- **Indicator values**: for each resolved indicator, its value at the alert bar
  plus its path over the window (so direction and level are both readable — the
  RSI-never-cited gap above is a level+direction gap).
  - Computed via `dsl-eval`'s `org.hatrack.dsl.BarIndicatorSource.evaluate(name,
    args)`, already on the classpath and already used for strategy evaluation —
    **no new dependency**.
  - `BarIndicatorSource` consumes `commons.OHLCBar` and computes over the bar
    close, whereas the chart computes its overlays over `PriceSource.HA_CLOSE`.
    To keep the values identical to the drawn overlays, the window is mapped to
    `commons.OHLCBar` with **`ha_close` as the close** before evaluation. A
    raw-close basis is explicitly rejected: it would print numbers that disagree
    with the lines in the image beside them, which is the failure this block
    exists to prevent.
- **Both flows.** `buildUserMessage(StrategyAlert)` gains the same context block.
  It currently sends no bar values whatsoever, so it is the larger relative gain;
  a strategy alert must not remain the weaker of the two.
#### Part A.2 — quotable precision (follow-up, measured on the first live alert)

The first alert produced under Part A (`AMS.BME`, `strong_candle/bullish_strong`,
1d, bar 2026-08-10) was verified figure-by-figure against DynamoDB. Everything the
context block had already **computed and labelled** came out exact in the note:
`SMA(10) = 55.08` (recomputed 55.0830), its five-point path `51.92 → 52.75 → 53.63
→ 54.43 → 55.08`, `EMA(20) = 53.63` (SMA-seeded 53.6252), `ha_low == ha_open ==
56.74`, the 23 July trough's `ha_close 48.41` and `ha_low 48.08`.

The one wrong figure appeared exactly where the model had to **scan the bar grid
itself**: it wrote "ha_close values sliding from roughly 52.2 on 30 June", while
that row reads `ha_open 51.79760900267785 · ha_high 52 · ha_low 50.9 · ha_close
51.385`. So it took the right row, drifted the digits, and attached the wrong
measure's name. It also quoted 48.41 and 48.08 for the same day without saying one
was the close and the other the low, which reads as self-contradictory even though
both are right.

Observed rule: **pre-computed, labelled values survive; values the model must
extract from a grid drift.** Three changes follow from it, all display-only:

1. **Round printed values — adaptively, never to a fixed price-shaped scale.**
   Bar rows and indicator values are shortened for display, because nothing in prose
   needs 14 significant figures and shorter numbers are reproduced more reliably.
   **Indicators are still computed on the full-precision series** — only the printed
   form is rounded, so this can never move a value.

   A flat 2 dp was the first implementation and it was wrong: it is a *price*-shaped
   format applied to values that are not all price-scaled (found by a Codex review of
   PR #87, two P2s). Two ways it destroys meaning:

   | Case | At flat 2 dp | Why it matters |
   |---|---|---|
   | MACD histogram near a zero cross: `0.0034`, `-0.0021` | `0.00`, **`-0.00`** | the cross the alert keyed on becomes invisible, and a negative zero is actively misleading |
   | Low-priced HA candle: `ha_open 1.001`, `ha_close 1.004` | `1.00`, `1.00` | the row shows an identical open and close while `colourOf` still labels it green from full precision — an internally contradictory context, exactly the confusion this section exists to remove |

   So the printed scale is chosen **per series**, as the smallest scale in
   `[2, 8]` that preserves the distinctions a reader depends on:
   - a value that is non-zero must not display as zero (so sign survives);
   - within a bar, an `open` and `close` that genuinely differ must not display
     as equal (so the row can never contradict its own colour label);
   - along an indicator path, adjacent points that genuinely differ must not
     display as equal (so direction and crossings survive).

   One scale per series, so columns stay aligned. Price-scaled series still land on
   2 dp — the case that motivated the change — while small-magnitude indicators keep
   the digits that carry their meaning.
2. **Pre-compute the anchors the model reaches for**, as labelled lines beside the
   indicator values: window first close, window lowest close with its date, window
   highest close with its date, the change from the low to the alert bar, and the
   change across the window. These are precisely the quantities the note derived by
   hand (`from X on 30 June`, `trough near 48.4 on 23 July`, `rallied ~18% from
   trough`), and they are the ones that went wrong.
3. **Require the measure to be named.** The SYSTEM_PROMPT instructs that a quoted
   bar value names its measure and date, and that a value must not be restated
   under another measure's name. This alone would have made the 48.41/48.08 pair
   self-explaining.

Deliberately NOT done: post-hoc numeric validation of the note against the context.
It is the most rigorous option and the most brittle, it can only reject a note it
cannot repair, and the authoritative figures are already in the pattern-values
table and the chart beside the prose. Revisit only if labelled anchors fail to hold
the error rate down.

Scope note: this is prose-precision only. It changes no grade, no chart, no news
path, and no indicator arithmetic.

```gherkin
Scenario: A price-scaled bar row is printed at two decimals
  Given an HA bar whose ha_open is 51.79760900267785
  When the technical context is built
  Then the row shows 51.80
  And it does not show 51.79760900267785

Scenario: Indicator values are rounded for display but computed at full precision
  Given a 30-bar window whose SMA(10) is 55.0830
  When the technical context is built
  Then the message shows SMA(10) = 55.08

Scenario: A near-zero indicator keeps the precision that carries its sign
  Given an indicator path containing 0.0034 and -0.0021
  When the technical context is built
  Then neither point displays as 0.00
  And the negative point still displays as negative

Scenario: A low-priced candle never shows an open equal to its close
  Given an HA bar whose ha_open is 1.001 and ha_close is 1.004
  When the technical context is built
  Then the displayed ha_open differs from the displayed ha_close
  And the row does not contradict its colour label

Scenario: The context states the window anchors the note would otherwise derive
  Given a 30-bar window whose lowest close is 48.41 on 2026-07-23
  When the technical context is built
  Then the message states the lowest close with its date
  And it states the change from that low to the alert bar

Scenario: The prompt requires a quoted bar value to name its measure
  When the AI analyst builds the request
  Then the system prompt instructs that quoted values name their measure
```

*Rationale for numbers over the PNG*: the sample shows the model reasoning
precisely from exact figures, and reading levels off a raster introduces
estimation error on exactly the values that matter (round-number resistance, MA
crossings, RSI thresholds), at higher token cost. The image remains available as
a later additive step.

**Spec correction (drift), applied:** the `USER` block in § Component 2 listed
`OHLC values: open=..., close=..., volume=...`, whereas the code sends
`open, high, low, close` and no volume. That line has been corrected in place and
a pointer to this Part A added there.

### Part B — news candidate pool: scope, over-fetch, filter, then cap

Order of operations becomes **scope the query → fetch wide → filter → rank → cap
to `TOP_N`**, replacing today's fetch-`TOP_N` → sort → cap.

**Design constraint: instrument-generic.** H-tchen supports any stock on any
supported exchange, so no step below may depend on per-instrument curated data.
There is **no company-name matching and no per-instrument text configuration** —
the only per-instrument input is the ticker/symbol already used to query. Every
filter is either provider-side entity resolution or a structural property of the
item itself. `Instrument.name` is deliberately unused: it is optional,
operator-entered, and unreliable across exchanges, ADRs and localized names.

1. **Push relevance to the provider (query-side entity scoping).** Each adapter
   asks its provider to resolve the symbol as an *entity* rather than accepting
   loose tag matches. Generic per provider, zero curation:
   - **Marketaux**: add `must_have_entities=true` to the existing
     `/v1/news/all?symbols=…&published_after=…` query, so only articles where the
     requested symbol is an identified entity are returned. (Marketaux tracks
     entities across its source set and links each article to its entities.)
     `filter_entities` is deliberately **not** sent: it trims each hit's returned
     entity array to the requested symbol, which would make every item look
     single-entity and silently disable the step-3 cardinality filter for this
     provider. Leaving it off keeps the full array available to count. (Settles
     this block's implementation decision 3.)
   - **EODHD**: the query is already symbol-scoped (`s={SYMBOL}` plus `from`/`to`).
   - **Yahoo RSS**: already a per-ticker feed; nothing to scope.
2. **Over-fetch.** Each provider is asked for `monitoring.news.candidate-pool`
   items (default 30) rather than `TOP_N`. `TOP_N = 5` remains the number of
   items handed to the model; only the candidate pool grows.
3. **Entity-cardinality filter (the generic digest killer).** Drop any item whose
   provider-supplied entity/symbol list exceeds
   `monitoring.news.max-entities-per-item` (default 6). An article tagged with a
   dozen tickers is a multi-company digest by construction; an article about one
   company carries one or a few. This is what actually kills the observed failure
   mode — the five other-companies' Q2 earnings digests that consumed NVDA's
   entire candidate budget — and it needs no knowledge of *which* company.
   - Applied **inside each provider adapter**, because the entity/symbol list
     lives in the raw payload and is not carried on `NewsHeadline` (EODHD's
     `symbols`/`tags`, Marketaux's `entities`; both currently parsed and
     discarded). This keeps `NewsHeadline` unchanged, per Block 17's precedent of
     not widening the domain record.
   - A provider that supplies no entity list (Yahoo RSS) applies no cardinality
     filter and is **not** penalized — the signal is optional by design.
4. **Uniform date-window filter.** Drop any item published outside the resolved
   `RecencyWindowSource` window for the alert's timeframe, applied centrally in
   `NewsAggregator` — it needs only `publishedAt`, which `NewsHeadline` already
   carries, so one implementation covers every provider including Yahoo RSS,
   which supplies no date filter of its own. This is the filter whose absence let
   February items reach a July bar.
5. **Global promotional-shape filter.** Drop items matching advertorial and
   screener-digest *title* shapes — the "X vs Y: Which Is the Better Value
   Stock?" comparison form and numeric listicles — which the sample's notes
   explicitly discard in 8 of 15 cases. The pattern list is **global and
   config-driven**: one list for all instruments, never per-instrument, so it
   scales to any stock.
6. **Rank, then cap.** Surviving items rank by the existing recency sort; the top
   `TOP_N` go to the model. Existing dedup (URL, normalized title within one
   hour) runs unchanged.
7. **Do not fail on an empty result.** If filtering removes everything, the tool
   returns zero headlines and the model's existing "say so rather than padding"
   instruction applies. An honest `news_headlines(0)` is the correct outcome and
   is strictly more informative than five irrelevant items.

**Where each step lives.** Steps 1 and 3 need the raw payload → provider
adapters. Steps 4, 5 and 6 need only `NewsHeadline` → `NewsAggregator`, so they
are written once and apply to every present and future provider.

The SYSTEM_PROMPT RELEVANCE block from Block 17 stays. Server-side filtering and
AI-side triage are complementary: the filter guarantees the model's five slots
are spent on plausible candidates; the triage remains the final judgment.

### Behaviour (Gherkin)

`features/news/news_aggregation.feature` additions:

```gherkin
Scenario: The candidate pool is over-fetched, filtered, then capped to TOP_N
  Given the candidate pool size is 30
  And the maximum entities per item is 6
  And the enabled news providers are "eodhd"
  And the EODHD provider holds 30 items for "NVDA" on "NASDAQ" within the recency window
  And 6 of them carry 2 symbols each and the rest carry 20 symbols each
  When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
  Then the provider was queried for 30 items
  And 5 headlines are returned
  And every returned headline is one of the 6 low-cardinality items

Scenario: A multi-company digest is dropped on entity cardinality
  Given the enabled news providers are "eodhd"
  And the maximum entities per item is 6
  And the provider returns an item titled "Q2 2026 earnings call summaries" carrying 14 symbols including "NVDA"
  When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
  Then 0 headlines are returned

Scenario: A single-company item is kept regardless of which company it is (control)
  Given the enabled news providers are "eodhd"
  And the maximum entities per item is 6
  And the provider returns an item carrying 1 symbol "AMS.MC"
  When I fetch news headlines for "AMS" on "BME" with max 5
  Then 1 headline is returned

Scenario: A provider supplying no entity list is not penalized
  Given the enabled news providers are "yahoo-rss"
  And the maximum entities per item is 6
  And the Yahoo RSS provider returns an item published 2 days before the bar with no symbol list
  When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
  Then 1 headline is returned

Scenario: The Marketaux query requests entity-resolved articles only
  Given the enabled news providers are "marketaux"
  When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
  Then the Marketaux provider was queried with must_have_entities true
  And the Marketaux provider was not queried with filter_entities

Scenario: An item outside the recency window is dropped even from a provider with no date filter
  Given the enabled news providers are "yahoo-rss"
  And the pattern timeframe is "1d"
  And the Yahoo RSS provider returns an item published 60 days before the bar
  And the Yahoo RSS provider returns an item published 2 days before the bar
  When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
  Then 1 headline is returned
  And it is the item published 2 days before the bar

Scenario: A promotional screener title shape is dropped
  Given the enabled news providers are "eodhd"
  And the global promotional patterns include the screener-comparison shape
  And the provider returns an item titled "SIG vs CFRUY: Which Is the Better Value Stock?"
  When I fetch news headlines for "CFR" on "SWX" with max 5
  Then 0 headlines are returned

Scenario: The promotional filter is instrument-independent
  Given the enabled news providers are "eodhd"
  And the global promotional patterns include the screener-comparison shape
  And the provider returns an item titled "AAA vs BBB: Which Is the Better Value Stock?"
  When I fetch news headlines for "GAW" on "LSE" with max 5
  Then 0 headlines are returned

Scenario: Filtering everything out yields zero headlines, not a failure
  Given the enabled news providers are "eodhd"
  And every item the provider returns is outside the recency window
  When I fetch news headlines for "NVDA" on "NASDAQ" with max 5
  Then 0 headlines are returned
  And no error is raised
```

**Part A is verified as AAA unit tests in `BedrockAiAnalystTest`, not Cucumber.**
There is no `features/alert/ai_analysis.feature` in this project and the analyst
is not Cucumber-driven: `BedrockAiAnalystTest` mocks `BedrockRuntimeClient` and
captures the `ConverseRequest`, which is the seam that can assert on the message
actually sent. This follows Block 17's precedent for prompt-contract pinning and
matches this block's own `Config and constraints` clause. The scenarios below are
the behavioural specification; each maps to one AAA test over the captured
request.

```gherkin
Scenario: The pattern-alert user message carries the chart's lookback series
  Given the chart lookback is 30 bars
  And a detected pattern on "NVDA" on "NASDAQ" at "1d"
  When the AI analyst builds the user message
  Then the message contains 30 HA bars with ha_open/ha_high/ha_low/ha_close and colour
  And the message still carries the alert bar's raw OHLC from the event snapshot

Scenario: The serialized indicators match the chart's resolved indicator set
  Given the chart config has sma-period 10, ema-period 20 and show-rsi true
  When the AI analyst builds the user message
  Then the message contains SMA(10), EMA(20) and RSI(14) values at the alert bar

Scenario: An indicator disabled in config appears in neither chart nor message
  Given the chart config has sma-period 0 and show-rsi false
  When the AI analyst builds the user message
  Then the message contains no SMA value
  And the message contains no RSI value

Scenario: An indicator whose period exceeds the window is omitted from the message
  Given the chart lookback is 30 bars
  And the chart config has ema-period 200
  When the AI analyst builds the user message
  Then the chart omits the EMA overlay
  And the message contains no EMA value

Scenario: A strategy alert carries the strategy's derived indicators
  Given a strategy whose conditions reference "rsi(20)" and "macd_line(12,26,9)"
  And a strategy alert fires for "CFR" on "SWX" at "1d"
  When the AI analyst builds the user message
  Then the message contains the lookback series
  And the message contains RSI(20) and MACD(12,26,9) values at the alert bar

Scenario: Indicator values are computed on the HA close, matching the drawn overlays
  Given the chart config has sma-period 10
  And the HA closes differ from the raw closes over the window
  When the AI analyst builds the user message
  Then the SMA(10) value equals the mean of the last 10 HA closes

Scenario: The strategy note describes the bar window the caller charted, not a re-read
  Given the caller passes a 3-bar window ending at the alert bar
  When the AI analyst builds the user message
  Then the message reports 3 raw OHLC bars
  And the message contains the alert bar's timestamp

Scenario: Indicators beyond the chart's eight subplot slots are not described
  Given a strategy referencing nine distinct oscillator periods
  When the AI analyst builds the user message
  Then the message describes the eight the chart draws
  And it does not describe the ninth
```

Volume is **not** carried: `HABar` has no volume field, and the window is the HA
series (above). The `monitoring.chart.show-volume` sub-panel is therefore outside
Part A.

### Config and constraints

- New key `monitoring.news.candidate-pool` (default 30) — per-provider fetch size
  before filtering. `TOP_N` stays 5 as the post-filter cap.
- New key `monitoring.news.max-entities-per-item` (default 6) — the entity/symbol
  cardinality ceiling.
- New key for the **global** promotional-title pattern list (config-driven, not
  compiled in, never per-instrument).
- No new per-instrument configuration of any kind. `Instrument.name` stays unused.
- `monitoring.chart.*` is reused unchanged as the single source of the indicator
  set; **no new chart config keys**, and no chart-rendering behaviour changes.
**Change boundary.** Part A cannot be delivered without touching code outside the
news layer, so the boundary is stated explicitly rather than left to judgment.

*In bounds — this block changes these:*

- `HeerwischChartRenderer`: the config-driven indicator resolution is **extracted**
  into a shared helper so the renderer and the prompt builder call the same code.
  Pure refactor — the resolved list, pane placement, dedup, the period-exceeds-window
  skip, and the rendered output are all unchanged.
- `StrategyChartSpec` + `HeerwischChartRenderer`: their two copies of the identical
  placement loop (dedup, window skip, eight subplot slots) collapse into the shared
  `ChartIndicatorPlacement`. Pure refactor on both — same drawn set, same panes,
  same order, same image — undertaken because the context builder is now a third
  consumer and three copies of a rule is three chances to drift.
- A new `TechnicalContextBuilder` (`infrastructure`), owning `HaRepository`,
  `ChartConfig`, the shared resolver and `BarIndicatorSource`. It owns **no**
  `OhlcRepository` and **no** `StrategyRepository`: for the strategy flow both the
  strategy and the bar window arrive as parameters.
- `BedrockAiAnalyst`: constructor gains the builder; both `buildUserMessage`
  overloads emit the context block.
- `NewsAggregator`, `EodhdNewsProvider`, `MarketauxNewsProvider`: per Part B.
- `NewsConfig` plus the three new keys above.

- **The `AiAnalyst` port and the strategy dispatch/retry paths** — boundary widened
  mid-increment, deliberately and on request, after a Codex review of PR #86 found
  that re-looking-up the strategy by instrument id can yield a different strategy
  than the one that fired and was charted. `analyze` gains a strategy-bearing
  overload, `StrategyAlertDispatchService` passes the firing strategy it already
  holds, and `StrategyRetryPollerService` hoists its single lookup so the chart and
  the note are drawn from the *same* `Strategy` object.

  A second Codex pass extended the same argument to the **bar window**: the chart is
  drawn from a repaired list (fresh-bar merge on the main path, trigger-bar splice on
  retry) that a re-read cannot reproduce. So the strategy overloads carry
  `List<OHLCBar>` too, and both call sites hand over the list they already hold —
  `StrategyRetryPollerService` hoisting it out of `reRenderChart` for the same reason
  it hoisted the strategy lookup.

  **Sharing the window MUST NOT make degradation depend on bar storage.** Hoisting the
  read out of `reRenderChart` moved it ahead of the `strategy.flatMap(...)` that used to
  short-circuit it, so on the **strategy-deleted** path — which previously never read
  bars at all — a transient failure began escaping `processOne`. `processBatch` has no
  per-item guard, so that aborted the entire retry batch and lost the degraded send this
  path exists to produce. Obtaining the window is therefore **best-effort**: any failure
  degrades to an empty window, which yields a chart-degraded send and a context-free note
  — exactly the pre-existing outcome.

  The catch is `RuntimeException`, not `DependencyUnavailableException`, because
  `DynamoDbOhlcRepository.findLastN` calls `client.query` **without wrapping**: a real
  outage arrives as a raw AWS SDK exception, so catching only the domain type would have
  read as a fix while leaving the actual failure mode untouched.

  Recorded because it is a regression this increment introduced, and a live invariant for
  anyone who moves that read again. Note what is *not* changed here: `processBatch` still
  has no per-item guard, so any other per-item throw still aborts the batch. Making the
  batch loop fault-isolating is a change to retry semantics for every failure mode and is
  deliberately left as its own decision, not smuggled in behind this fix.

  **Invariant established:** the note describes the strategy *and* the series the
  chart was rendered from, because they are the same objects — not second derivations
  that happen to agree. `TechnicalContextBuilder` therefore holds neither a
  `StrategyRepository` nor an `OhlcRepository`, which removes both races rather than
  narrowing them. Stated as a rule for future readers: **anything the note describes
  about a rendered artefact is passed in, never looked up.**

  The no-strategy overload is retained for the genuinely degraded retry case (the
  strategy was deleted since detection, which already forces a chart-degraded
  send): it yields the bar series with no indicators. `Optional` is not used as a
  parameter, per §13.

- **The `ChartRenderer` port and the pattern dispatch/retry paths** — boundary widened a
  second time, on request, after the reviewer applied the invariant above to the pattern
  flow too. Both were listed out of bounds below; the entry is moved here rather than
  quietly deleted, because the original reason for excluding them still stands and was
  simply outweighed.

  The asymmetry that forced it: in the strategy flow the caller already held the bars, so
  passing them cost nothing. In the pattern flow the *renderer* fetched its own window, so
  there was no way for the analyst to receive the drawn bars without changing the port.
  Choosing correctness over the narrower diff means `renderChart` takes the window,
  `AlertDispatchService` / `RetryPollerService` resolve it once, and the two consumers can
  no longer diverge — closing the whole finding class structurally instead of case by case.

  Rejected alternative: having `renderChart` *return* the window it used. It would also
  guarantee one read, but it makes the port's return type carry drawing internals and it
  would leave the two flows with different shapes. Passing the window in matches the
  strategy flow exactly, so there is one rule to remember rather than two.

*Out of bounds — unchanged, and a reason to stop and report if pressure builds:*

- Fault isolation in the retry batch loops. `processBatch` still has no per-item guard, so
  a per-item throw other than the bar-window read still aborts the batch. Changing that
  alters retry semantics for every failure mode and is its own decision.
- `NewsHeadline`, `HABar`, provider enablement, dedup rules, `AlertEnrichment`.
- Chart rendering output — no visual change to the emailed image.
- Any new Maven dependency.
- Prompt-contract verification follows Block 17's precedent: `BedrockAiAnalystTest`
  pins the presence and shape of the Part A context block in the message sent to
  Bedrock, so a regression fails CI. No attempt to unit-test the model's judgment.
- **Verification mode**: the grade distribution is assessed against real alert
  emails in a follow-up session, as in Block 17. Target signal: the LOW share
  falls on days with a genuine same-day company-specific catalyst, and notes begin
  citing indicator levels instead of hedging about them.

**Decisions to settle before implementation:**

1. The default value of `monitoring.news.max-entities-per-item`. 6 is a starting
   estimate, not a measured threshold: the sample's digests carried enough tickers
   to be unambiguous, but the true separating value between "digest" and
   "multi-company story worth reading" is unmeasured. Log the dropped count per
   run so the value can be tuned on evidence rather than guessed twice.
2. Whether the global promotional-pattern list ships with a seed set derived from
   the 15-email sample, or starts empty and is populated operationally.
3. ~~Whether `filter_entities=true` interferes with the step-3 cardinality
   count~~ — **SETTLED**: it does, by definition (it trims each hit's entity array
   to the requested symbol). Only `must_have_entities=true` is sent; see Part B
   step 1.

**Queued decisions (deliberately NOT in this increment)**: the five unimplemented
fundamentals tools; multimodal chart image; per-alert id; instrumented
`data_sources`; same-bar alert dedup; `AlertEnrichment` coverage semantics.

---

## Dependency order

| Block | Supersedes | On H-tchen critical path | Depends on |
|---|---|---|---|
| 11 adapter | — | yes | — |
| 12 HA | Block 4 | yes | 11 |
| 13 ingest | Block 3 | yes | 11 |
| 14 chart | Block 6 (chart half) | yes | — |
| 15 detection | Block 5 | yes | 11 |
| 16 strategy behaviour | — | yes | 15 |
| 17 EODHD news + triage | — | no | 13 (suffix map + token), Block 3 aggregation |
| 18 confidence inputs | Block 17 (two `Out of scope` bullets — see its Supersedes) | no | 14 + Component 1b (chart indicator sets), 17 (news providers), Block 3 aggregation |

Suggested order: 11 → 15 → 16 (unlocks the requested value at lowest risk), then 12 → 13 → 14 (consolidation, rising risk). 12/13/14 are independent of each other after 11.

## Decisions to settle during implementation (not blocking)

- The JSON strategy schema field list (H-tchen artifact).
- Memo language: technical verbatim (chosen provisionally) vs plain-language rephrase.

## External dependencies (other repos — tracked, not specified here)

- ha-track: NO CHANGE.
- wichtelm-app: a `.strat` -> H-tchen JSON export utility. Must verify each `.strat` primitive maps to a nachtkrapp `DetectionRule`; gaps surface there. Optional, later.

---

## Deferred / known issues

Single place collecting everything intentionally postponed. Each entry: what it
is, why it's deferred, and when/how to address it.

1. **Strategy dispatch path** — RESOLVED (PR #80, SI-1..SI-3c.3).
   `detectStrategyAlert` is now wired into `MonitoringRunService` → the dedicated
   `StrategyAlertDispatchService` (chart → AI → email, retry via SI-3c.3), the
   `NoOp` repository is replaced by `DynamoDbStrategyRepository` (a real `STRATEGY`
   item), and the Block 15 supersede semantics are restored (SI-3b: `detectPatterns`
   returns empty when a strategy exists, so an instrument is never both suppressed
   AND undispatched).

2. **Strategy lookback by bar count, not calendar seconds** — RESOLVED (PR #79
   review #5 / PR #80 review).
   `PatternDetectionService.detectStrategyAlert` reads the last `STRATEGY_LOOKBACK_BARS`
   persisted OHLC bars **by count** (`OhlcRepository.findLastN(instrumentId, tf,
   latest, n)`, inclusive of the latest bar), not a `period_seconds(tf)` calendar
   window. Bar-count is the parity-correct approach (the runtime twin of the
   import-time `IndicatorWarmupException` check): market gaps (weekends, holidays,
   missing data) no longer shrink the warmup window, so a long indicator such as
   `rsi(250)` keeps alerting as long as enough bars are persisted. Verified reference:
   wichtelm windows by bar count (`barsStrictlyBefore` / warmup-by-bars).

3. **Stateless-parametric evaluation via a future mail "commit" button.**
   *What*: a user-supplied entry price would let `entry_price`-relative conditions
   (stop-loss / take-profit) be *evaluated* rather than only quoted as memo. (Also
   noted under Block 16 → Out of scope.)
   *Why deferred*: H-tchen is stateless by design and the strategy format isn't
   proven yet; not on the critical path.
   *To address*: revisit once the strategy format is proven, alongside the dispatch
   work (item 1) — would need a mail-side "commit" affordance that feeds an entry
   price back into evaluation.

4. **Optimistic locking via the `version` attribute.**
   *What*: per-instrument `CONFIG` updates are last-write-wins; there is no optimistic
   concurrency guard. (Also noted under Block 2 → "Concurrent updates (last-write-wins)".)
   *Why deferred*: concurrent config edits are rare for the current CLI-driven,
   single-operator workflow.
   *To address*: add a `version` attribute and a `ConditionExpression` on the
   `UpdateItem` if/when a multi-writer UI makes concurrent edits likely.

5. **Purge pending retries when a strategy is changed/removed.** A queued
   `STRATEGY_PENDING_ALERT` belongs to the strategy that fired it; if the strategy
   is re-imported, edited, or deleted before the retry runs, the in-flight retry is
   stale. The desired behaviour is to drop the instrument's pending retries on that
   change. *Why deferred*: (a) there is **no in-app strategy change/remove
   operation** to hook today — strategies are loaded out-of-band and
   `StrategyRepository` is read-only (the `mon` management CLI is planned, not
   built), the only removal being full instrument hard-delete; (b) pending retries
   are **not indexed by instrument** (pk = `STRATEGY_PENDING_ALERT#<event_uid>`, the
   only GSI is by `retry_at`), so finding an instrument's retries needs a scan or a
   new index; (c) it is **self-limiting** — a pending retry lives only ~3 attempts
   (~hours) before it expires, so the worst case is one slightly-stale email. A
   snapshot-the-firing-strategy fix was built and **reverted** as over-engineered
   for a rare, self-correcting case. *To address*: add
   `PendingStrategyAlertRepository.deleteByInstrumentId` (with an instrument-keyed
   access path) and call it from the strategy import/delete flow **when that
   command is built** — it is the natural owner of this cleanup, and also lets
   instrument hard-delete drop orphaned pending retries.

### From code (TODO/FIXME)

- (none currently)

---

## 14. Configuration Appendix

### SSM Parameter Store (consolidated)

| Path                                              | Type          | Default                                          | Notes                              |
|---------------------------------------------------|---------------|--------------------------------------------------|------------------------------------|
| `/monitoring/eodhd/base-url`                      | String        | `https://eodhd.com/api`                          | EODHD history API                  |
| `/monitoring/eodhd/timeout-seconds`               | String        | `10`                                             |                                    |
| `/monitoring/eodhd/api-key`                       | SecureString  | —                                                | env `MONITORING_EODHD_API_KEY`     |
| `/monitoring/eodhd/news/summary-max-chars`        | String        | `600`                                            | EODHD news `content` → summary truncation (Block 17) |
| `/monitoring/marketaux/base-url`                  | String        | `https://api.marketaux.com/v1`                   | Marketaux news API                 |
| `/monitoring/marketaux/api-key`                   | SecureString  | —                                                | env `MONITORING_MARKETAUX_API_KEY` |
| `/monitoring/marketaux/recency-days-1d`           | String        | `7`                                              | `published_after` window, daily timeframe; env `MONITORING_MARKETAUX_RECENCY_DAYS_1D` |
| `/monitoring/marketaux/recency-days-1w`           | String        | `30`                                             | `published_after` window, weekly timeframe; env `MONITORING_MARKETAUX_RECENCY_DAYS_1W` |
| `/monitoring/news/providers`                      | String (list) | `marketaux,yahoo-rss,eodhd`                      | enabled news adapters              |
| `/monitoring/bootstrap/size-1d`                   | String        | `250`                                            |                                    |
| `/monitoring/bootstrap/size-1w`                   | String        | `260`                                            |                                    |
| `/monitoring/exchanges/supported`                 | String (csv)  | `NASDAQ,NYSE,MIL,XETRA,LSE,TSX,PAR,AMS,SWX,BME`  |                                    |
| `/monitoring/exchanges/suffix-map`                | String (json) | EODHD codes — `{"NASDAQ":".US","XETRA":".XETRA","LSE":".LSE",...}` |                   |
| `/monitoring/exchanges/news-suffix-map`           | String (json) | common market codes — `{"NASDAQ":"","XETRA":".DE","LSE":".L",...}` | shared by the Marketaux + Yahoo-RSS news adapters |
| `/monitoring/ingest/circuit-breaker.threshold`    | String        | `3`                                              |                                    |
| `/monitoring/ingest/failure-rate-alert`           | String        | `0.5`                                            |                                    |
| `/monitoring/ingest/max-retries`                  | String        | `3`                                              | transient `fetchHistory` retries   |
| `/monitoring/ingest/retry-base-delay-ms`          | String        | `1000`                                           | backoff base; doubles each retry   |
| `/monitoring/bedrock/model-id`                    | String        | `anthropic.claude-haiku-4-5-20251001-v1:0`       |                                    |
| `/monitoring/bedrock/region`                      | String        | `eu-central-1`                                   |                                    |
| `/monitoring/bedrock/max-tokens`                  | String        | `1500`                                           |                                    |
| `/monitoring/bedrock/max-tool-iterations`         | String        | `8`                                              |                                    |
| `/monitoring/bedrock/tool-timeout-seconds`        | String        | `5`                                              |                                    |
| `/monitoring/chart/lookback-bars`                 | String        | `30`                                             |                                    |
| `/monitoring/chart/width-px`                      | String        | `900`                                            |                                    |
| `/monitoring/chart/height-px`                     | String        | `500`                                            |                                    |
| `/monitoring/chart/show-volume`                   | String        | `false`                                          |                                    |
| `/monitoring/email/subject-prefix`                | String        | `[HA Alert]`                                     |                                    |
| `/monitoring/email/charset`                       | String        | `UTF-8`                                          |                                    |
| `/monitoring/ses/sender-email`                    | SecureString  | —                                                | populated manually                 |
| `/monitoring/ses/region`                          | String        | `eu-central-1`                                   |                                    |
| `/monitoring/ses/reply-to`                        | String        | (empty)                                          | optional                           |
| `/monitoring/alerts/audit-enabled`                | String        | `false`                                          |                                    |
| `/monitoring/retry/max-attempts`                  | String        | `3`                                              |                                    |
| `/monitoring/retry/delay-seconds`                 | String        | `3600`                                           |                                    |
| `/monitoring/retry/poller-interval-minutes`       | String        | `15`                                             |                                    |
| `/monitoring/dynamodb/table-name`                 | String        | `monitoring`                                     |                                    |
| `/monitoring/dynamodb/gsi1-name`                  | String        | `gsi_status`                                     |                                    |
| `/monitoring/dynamodb/gsi2-name`                  | String        | `gsi_retry_due`                                  |                                    |

### Lambda environment variables

| Var                      | Example          | Notes                                |
|--------------------------|------------------|--------------------------------------|
| `AWS_REGION`             | `eu-central-1`   | injected by AWS                      |
| `LOG_LEVEL`              | `INFO`           |                                      |
| `MONITORING_TABLE`       | `monitoring`     | overrides default                    |

(Most config comes from SSM, fetched at startup.)

### IAM permissions (consolidated)

| Service       | Actions                                                                | Resource scope                                |
|---------------|------------------------------------------------------------------------|-----------------------------------------------|
| DynamoDB      | GetItem, PutItem, UpdateItem, DeleteItem, Query, BatchWriteItem, TransactWriteItems | `monitoring` table + GSI1 + GSI2     |
| DynamoDB      | DescribeTable                                                          | `monitoring` table                            |
| Bedrock       | InvokeModel, Converse                                                  | model ARN of the configured model              |
| SES v2        | SendEmail, SendRawEmail                                                | verified identity (sender)                    |
| SSM           | GetParameter, GetParameters                                            | path `/monitoring/*`                          |
| KMS           | Decrypt                                                                | key used by SSM SecureString                  |
| SNS           | Publish                                                                | DLQ topic                                     |
| CloudWatch Logs | CreateLogStream, PutLogEvents                                        | function log group                            |
| CloudWatch    | PutMetricData                                                          | namespace `Monitoring/HeikinAshi`             |

### Observability — custom CloudWatch metrics

Namespace: `Monitoring/HeikinAshi`.

| Metric                       | Dimensions                | Unit     | Emitted when                    |
|------------------------------|---------------------------|----------|---------------------------------|
| `InstrumentsProcessed`       | `Run=main`                | Count    | end of `monitoring-main`        |
| `InstrumentsFailed`          | `Run=main`                | Count    | per-instrument failure          |
| `OHLCBarsInserted`           | `Timeframe`               | Count    | per-bar successful Put          |
| `HABarsComputed`             | `Timeframe`               | Count    |                                 |
| `PatternsDetected`           | `Pattern`, `Subtype`      | Count    | per emitted event               |
| `AlertsSent`                 | `Mode=full \| degraded`   | Count    | dispatch success                 |
| `AlertsQueuedForRetry`       | `Queue=pattern \| strategy` | Count  | enqueue a `PENDING_ALERT` or `STRATEGY_PENDING_ALERT` |
| `RetryItemsProcessed`        | `Queue=pattern \| strategy` | Count  | end of `retry-poller` (per queue drained) |
| `BedrockToolIterations`      | —                         | Average  | per AI call                     |
| `LambdaDurationSeconds`      | `Function`                | Seconds  | end of handler                  |

### Observability — alarms

| Alarm                              | Condition                                | Severity |
|------------------------------------|-------------------------------------------|----------|
| `MainNotRunning`                   | no invocation of `monitoring-main` in 26h | high     |
| `MainFailureRate`                  | `InstrumentsFailed / InstrumentsProcessed > 30%` | medium |
| `RetryBacklog`                     | `PENDING_ALERT` or `STRATEGY_PENDING_ALERT` items > 50 | medium   |
| `BedrockHighErrorRate`             | `LLMException` > 10/h                     | medium   |
| `LambdaErrors`                     | Lambda errors > 0                         | high     |
| `DLQDepth`                         | DLQ messages > 0                          | high     |

---

## 15. Closing roadmap (final)

| Block | Goal                                              | Status |
|-------|---------------------------------------------------|--------|
| 0     | Data Model                                        | ✅     |
| 0.B   | Internal error catalog                            | ✅     |
| 1     | Domain ops: registry                              | ✅     |
| 2     | Domain ops: config                                | ✅     |
| 3     | Quote ingestion (EODHD + news aggregation)        | ✅     |
| 4     | Heikin Ashi computation                           | ✅     |
| 5     | Pattern detection                                 | ✅     |
| 6     | Rich dispatch (JFreeChart + Bedrock + Commons Email) | ✅  |
| 7     | Orchestration                                     | ✅     |
| 8     | Test strategy                                     | ✅     |
| 9     | IaC (Terraform + GitHub Actions)                  | ✅     |
| 10    | Code style & testing approach                     | ✅     |

End of CLAUDE.md.
