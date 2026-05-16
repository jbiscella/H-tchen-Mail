<p align="center">
  <img src="h-tchen_logo.png" alt="H-tchen Mail logo" width="200">
</p>

# Heikin Ashi Monitoring Service

**A daily watchlist sentinel for retail investors.** You hand it a handful of
stocks; it watches them for you and emails you only when one does something
worth a second look — so you don't have to sit staring at charts.

Most days nothing happens and no mail arrives. When a stock forms a
meaningful *Heikin Ashi* candle pattern — a possible trend reversal, a burst
of one-sided momentum, or a moment of market indecision — you get a single
email: the chart, the numbers, and a short plain-language note from an AI
analyst that pulls in recent news to give the signal context. It runs
entirely on AWS Lambda — no server, no dashboard, no app to open. **The inbox
is the interface.**

It is a *notification* tool, not an advisor: every alert is a prompt to go
look, never an instruction to trade.

## What an alert looks like

![Example Heikin Ashi alert email](email-example.png)

## Understanding the alerts

**Heikin Ashi** (Japanese for "average bar") is a way of redrawing the normal
price candles so each one is blended with the bar before it. The result is a
smoother chart where trends and turning points stand out and day-to-day noise
fades — easier to read at a glance than raw candlesticks.

The service watches for three patterns on those smoothed candles:

| Pattern | Plain meaning | Why it's worth an email |
|---|---|---|
| **Color change** | A run of same-coloured candles (green = rising, red = falling) is broken by the opposite colour. | Heikin Ashi trends tend to hold one colour; the first flip after a streak is a classic early hint that the trend may be turning. |
| **Strong candle** | A big-bodied candle with almost no wick — price moved one direction decisively for the whole period. | Signals conviction and momentum behind the move, not a half-hearted drift. |
| **Doji** | A candle with a tiny body — it opened and closed at nearly the same price. | A tug-of-war between buyers and sellers: indecision, which often sits right before a turn. |

Each pattern is tunable per stock (how long a streak must run, how small a
body counts as a doji, and so on), and you choose which ones you care about.
The email tells you which pattern fired, on which timeframe (daily or
weekly), and highlights the exact candle that triggered it — then the AI note
weighs recent news for and against the signal so you have context before you
decide whether to act.

## How it works

Once a day an EventBridge cron fires the `monitoring-main` Lambda, which for
each active instrument runs four stages:

1. **Ingest** — fetch closed daily/weekly OHLC bars from EODHD, persist them
   idempotently to DynamoDB, apply the per-instrument storage policy.
2. **Heikin Ashi** — compute HA candles from the OHLC chain, deterministically
   and idempotently (`BigDecimal` arithmetic end-to-end).
3. **Detect** — evaluate three patterns (color change, strong candle, doji)
   on the freshly computed bars. Only the most recent bar can raise an alert,
   so a 250-bar first ingest doesn't trigger an alert storm.
4. **Dispatch** — for each detected event render a JFreeChart HA chart, ask
   Claude on AWS Bedrock for a fundamental-analysis note (the model pulls
   headlines from Marketaux + Yahoo Finance RSS through a tool-use loop),
   compose a multipart email and send it via SES. Failed enrichment is queued
   in DynamoDB and retried by a second Lambda every 15 minutes, degrading to a
   plain alert after three attempts.

You can also invoke `monitoring-main` manually — scoped to specific instrument
ids, or with `force_email` to smoke-test the whole chart + AI + email path
without waiting for a real pattern (see [`DEPLOY.md`](DEPLOY.md)).

## Documentation map

| Document | What it covers |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | **Authoritative behavioral specification.** Architecture decision record, single-table data model, full error catalog, per-block Gherkin scenarios, code-style rules, observability + IAM + config appendix. The source of truth for *what* the system does and *why*. |
| [`DEPLOY.md`](DEPLOY.md) | **Step-by-step deploy procedure on a fresh AWS account — also the fork-and-deploy-your-own guide.** Day-0 async prereqs (SES production access, Bedrock model access), bootstrap apply, GitHub repo secrets + variables, sender-email secret, first push, verification, common snags, rollback. Also contains the **"Adding an instrument"** recipe (raw `aws dynamodb transact-write-items` until the `mon` CLI lands). |
| [`terraform/bootstrap/README.md`](terraform/bootstrap/README.md) | Bootstrap stack reference: state bucket, lock table, GitHub OIDC provider, deploy IAM role + trust policy. Applied once per account, manually. |
| [`terraform/main/README.md`](terraform/main/README.md) | Main stack reference: per-file resource catalog, the code-vs-shape deploy model and `lifecycle { ignore_changes }` rationale, manual prerequisites, ops notes. |
| [`THIRD_PARTY_LICENSES.txt`](THIRD_PARTY_LICENSES.txt) | Auto-generated full enumeration of every direct + transitive Maven dependency with licenses. Refreshed on every `mvn verify`. |
| [`LICENSE`](LICENSE) | BSD-0 project license + third-party license families summary. |
| [`context-diary/`](context-diary/) | Periodic handoff snapshots intended for fresh agent/session pickup. |

## Stack

| Layer            | Choice                                                   |
|------------------|----------------------------------------------------------|
| Language         | Java 25 (Amazon Corretto)                                |
| Framework        | Micronaut 4.10.x                                         |
| Build            | Maven                                                    |
| Compute          | AWS Lambda (JVM + SnapStart, arm64)                      |
| Database         | DynamoDB single-table, on-demand                         |
| Market data      | EODHD end-of-day API (behind a `MarketDataProvider` port)|
| News             | Marketaux + Yahoo Finance RSS (aggregated + deduplicated)|
| AI               | AWS Bedrock + Claude (Converse API, manual tool loop)    |
| Charting         | JFreeChart (headless)                                    |
| Email            | Apache Commons Email + SES v2 raw send                   |
| IaC              | Terraform                                                |
| CI/CD            | GitHub Actions (OIDC to AWS)                             |

## Repository layout

```
.
├── CLAUDE.md                       # authoritative specification
├── DEPLOY.md                       # step-by-step deploy runbook
├── README.md                       # you are here
├── LICENSE                         # BSD-0 + third-party summary
├── THIRD_PARTY_LICENSES.txt        # full dep enumeration (auto-generated)
├── pom.xml                         # Maven build
├── src/
│   ├── main/
│   │   ├── java/                   # domain / application / infrastructure / orchestration
│   │   └── resources/
│   │       ├── application.yml     # Micronaut defaults (overridden by Lambda env vars)
│   │       └── logback.xml         # JSON logging config
│   └── test/
│       ├── java/                   # unit + Cucumber + LocalStack ITs
│       └── resources/
│           └── features/           # Cucumber .feature files, grouped by functional scope
├── terraform/
│   ├── bootstrap/                  # state bucket + OIDC (apply once, manually)
│   └── main/                       # runtime stack (DynamoDB, Lambdas, EventBridge, SES, alarms)
├── .github/workflows/ci.yml        # mvn verify + terraform plan/apply + Lambda deploy
└── context-diary/                  # handoff snapshots for fresh sessions
```

The Java tree follows hexagonal layering as defined in CLAUDE.md
§13: `domain` → `application` → `infrastructure` → `orchestration`.
The `domain` layer must not import any AWS SDK or third-party
infrastructure type.

## Build

Requires JDK 25 and Maven 3.9+.

```bash
mvn verify           # compile + run tests + coverage gate
mvn package          # build the Lambda jar (Shade)
mvn -pl . test       # unit + integration tests
```

## Deploy

End-to-end procedure for a fresh AWS account: **[`DEPLOY.md`](DEPLOY.md)**.

Short version: deployment is automated via GitHub Actions on push to
`main` (OIDC federated, no static keys). Runtime configuration comes
from `application.yml` defaults overridden by Lambda environment
variables set in `terraform/main/lambda.tf`. The Terraform stack splits
into a one-shot manual **bootstrap** stack and a CI-applied **main**
stack — both have their own READMEs.

## Operations

CLAUDE.md §10 sketches a `mon` CLI that wraps the AWS SDK to manage
instruments without an HTTP API. The CLI is **planned**, not yet
implemented — for now:

- **Add an instrument** → see [`DEPLOY.md`](DEPLOY.md) §"Adding an
  instrument" for a copy-pasteable `aws dynamodb transact-write-items`
  recipe.
- **Manual one-shot run** of the daily pipeline against a specific
  instrument:

  ```bash
  aws lambda invoke \
    --function-name monitoring-main:live \
    --payload '{"instrument_ids": ["<uuid>"]}' \
    /dev/null
  ```
- **End-to-end pipeline smoke** (sends an email even if no pattern
  fires — useful to verify EODHD + Bedrock + SES wiring after a
  deploy without waiting for a real signal):

  ```bash
  aws lambda invoke \
    --function-name monitoring-main:live \
    --payload '{"force_email": true}' \
    /dev/null
  ```

  Each (instrument, tracked-timeframe) without a real pattern this run
  gets one synthetic `forced/forced` event built from its latest
  persisted HA + OHLC bar. Skips silently when no HA bar exists yet
  (first ingest). Combine with `instrument_ids` to scope it.

## License

Released under the [BSD Zero Clause License](LICENSE).
