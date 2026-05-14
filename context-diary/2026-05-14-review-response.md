# Context bundle — Heikin Ashi Monitoring Service

Snapshot intended for handoff to a fresh agent/session. The source of
truth for everything described here is `CLAUDE.md` at the repo root.

## What this is

A Java 25 / Micronaut 4.10 / AWS Lambda service that, once a day:

1. **Ingests** OHLC bars from Yahoo Finance for every active instrument.
2. **Computes** Heikin Ashi candles from those bars.
3. **Detects** three pattern families (color_change, strong_candle, doji)
   against per-instrument thresholds.
4. **Dispatches** a rich multipart email per detected pattern (JFreeChart
   inline + Bedrock-generated fundamental analysis), with async retry
   queue for transient chart / AI / SES failures.

Storage is a single DynamoDB table (PK/SK + GSI1/GSI2). Schedule is a
22:00 UTC EventBridge cron firing `monitoring-main`; a 15-minute cron
fires `retry-poller` against the `PENDING_ALERT` queue.

## Architectural ground rules (CLAUDE.md §1)

- Hexagonal: domain depends only on interfaces; adapters live in
  `infrastructure/`.
- Immutable: records for domain types, `BigDecimal` for prices, `Instant`
  UTC + injected `Clock` for time.
- Micronaut DI: `@Singleton` + `@Factory` + `@ConfigurationProperties`.
  **Never** Spring annotations (CLAUDE.md §13 anti-patterns).
- Java 25 + SnapStart (no Native Image).

## Repo layout

| Path | Purpose |
|---|---|
| `src/main/java/com/heikinashi/monitoring/domain/` | Records, sealed types, ports, error catalog |
| `src/main/java/com/heikinashi/monitoring/application/` | Pure services (registry, ingestion, HA, detection, dispatch, run); `application/config/` holds `@ConfigurationProperties` records |
| `src/main/java/com/heikinashi/monitoring/infrastructure/` | Adapters: `AppFactory` produces AWS SDK clients; `dynamodb/`, `bedrock/`, `email/`, `chart/`, `yahoo/` |
| `src/main/java/com/heikinashi/monitoring/orchestration/` | Lambda handlers: `MonitoringMainHandler`, `RetryPollerHandler` |
| `src/main/resources/application.yml` | Defaults; overridden by Lambda `environment.variables` at deploy time |
| `src/test/resources/features/{registry,configuration,ingestion,heikin_ashi,pattern_detection,dispatch,orchestration}/*.feature` | Cucumber acceptance (248 scenarios) |
| `src/test/java/com/heikinashi/monitoring/cucumber/` | Step defs + Pico `World` for shared scenario state |
| `src/test/java/com/heikinashi/monitoring/infrastructure/dynamodb/` | LocalStack-backed ITs (28 cases) |
| `terraform/bootstrap/` | State bucket, lock table, OIDC provider, deploy role — apply once manually |
| `terraform/main/` | Runtime stack: DynamoDB, Lambdas (SnapStart, arm64, alias `live`), EventBridge, SES, SNS DLQ, CloudWatch alarms, IAM |
| `.github/workflows/ci.yml` | `mvn verify` + `terraform fmt+validate` + (on push to main) terraform apply + Lambda jar deploy via OIDC |

## What works today

- App boots end-to-end. `AppFactoryWiringTest` verifies the Micronaut
  context resolves every AWS client + every application service.
- All 302 unit + Cucumber tests pass; JaCoCo gate **90% line / 80%
  branch** on the domain + application + wiring code; AWS adapters and
  Lambda handlers are excluded (LocalStack ITs cover the DDB adapters,
  but ITs run under Failsafe which the surefire-bound JaCoCo agent
  doesn't see).
- Terraform plans/applies cleanly. The Lambda-code-vs-shape split (TF
  owns shape, CI owns code via `update-function-code` + `publish-version`
  + `update-alias --name live`) is wired and respected by `lifecycle {
  ignore_changes = [...] }` blocks.
- THIRD_PARTY_LICENSES.txt auto-regenerates on every `mvn verify`.

## Recent work (review-response series, PRs #22–#27)

Six small PRs in response to a ChatGPT review of the codebase. Each
addresses one finding and ships independently.

| PR | What | Why |
|---|---|---|
| #22 PR-1 | `AppFactory` produces `DynamoDbClient` / `BedrockRuntimeClient` / `SesV2Client`. New `RetryConfig`, `AlertsConfig`, `RunConfig`, `AwsRegionConfig`, `SesConfig` records replace primitive constructor args on the three application services. `BedrockConfig` gains `region`. `EmailConfig` loses its `alerts@example.com` default. `YahooFinanceProvider` gets injected `Clock`. New `AppFactoryWiringTest`. | Without this, the app threw `NoSuchBeanException` at startup — two genuine boot-blockers. |
| #23 PR-2 | `BatchWriteRetry` helper: re-submits `BatchWriteItem.unprocessedItems` with exponential backoff (50/100/200 ms, max 4 attempts), throws `DependencyUnavailableException` on final failure. Both DDB repos route their `batchDelete` through it. | Classic silent-data-loss bug under DynamoDB throttling. |
| #24 PR-3 | `MonitoringRunService` dispatches per-instrument inline instead of accumulating into a run-wide buffer and flushing at the end. | Old flow lost every queued event when the soft timeout fired mid-loop. |
| #25 PR-4 | `SesEmailSender.deliver`'s catch block now classifies SES errors: throttling/5xx → `DependencyUnavailableException` (retry); `AccessDenied` / `AccountSendingPaused` / `MailFromDomainNotVerified` → new `SESConfigurationException` (DLQ-bound); `MessageRejected` + fallthrough → recipient-level `DeliveryResult`. | Used to treat every SES failure as a recipient-level problem. |
| #26 PR-5 | New `OhlcRepository.findRange(id, tf, from, toInclusive)` overload; DDB impl uses `BETWEEN`, in-memory uses `TreeMap.subMap`. `PatternDetectionService` passes `[earliest, latest]` of the new HA window. | Old read was lower-bound only — under `FULL_HISTORY` scanned everything. |
| #27 PR-6 | README / `terraform/main/ssm.tf` / `ToolCatalog` Javadoc fixes (SSM-overlay claim, `mon` CLI vapor, missing tool cache). JaCoCo excludes narrowed from `infrastructure/**` to per-adapter subpackages so wiring code now counts toward the gate. | Docs-drift + coverage truth-up. |

## Explicitly deferred (planning decision, not bugs)

| Finding | Why deferred |
|---|---|
| Retry-poller lease/owner field before send | `reserved_concurrent_executions = 1` in `terraform/main/lambda.tf` plus EventBridge at-most-once-per-rule makes the duplicate-send race essentially unreachable. Revisit if concurrency is ever lifted. |
| SNAPSHOT_ONLY empty-store window during `>24` truncation | Only triggers under SNAPSHOT_ONLY (opt-in policy) + >24 existing items + concurrent reader inside a ~50 ms window. PR-2 fixed the high-impact half (silent loss); the atomicity rewrite was deemed over-engineering. |
| KMS `resources = ["*"]` tightening | The `kms:ViaService = ssm.<region>.amazonaws.com` condition is the idiomatic policy for AWS-managed SSM keys (rotating ARNs); tightening would be wrong. |
| Java 21 instead of Java 25 | ADR pins to Java 25; Lambda supports it. |
| SSM Parameter Store overlay (`micronaut-aws-parameter-store`) | Explicitly rejected: runtime config now comes from `application.yml` + Lambda env vars (set in `terraform/main/lambda.tf`). SSM block in `terraform/main/ssm.tf` is operator documentation only. |
| `mon` CLI implementation | Marked planned in README; no work item. |

## How to verify locally

```bash
# Requires JDK 25 (Corretto). Docker daemon needed for *IT.java.
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64

mvn -B -ntp -DskipITs verify   # 302 unit/Cucumber tests, ~2.5 min
mvn -B -ntp verify             # + 28 LocalStack ITs, ~5-6 min

# Terraform (no apply, no AWS):
cd terraform/bootstrap && terraform init -backend=false && terraform validate
cd terraform/main      && terraform init -backend=false && terraform validate
terraform fmt -check -recursive
```

## Deploy prerequisites (first time on an AWS account)

1. `terraform apply` in `terraform/bootstrap/` (creates state bucket + lock table + OIDC + deploy role).
2. Wire repo Settings → Variables: `DEPLOY_ROLE_ARN`, `AWS_REGION`, etc. (see `.github/workflows/ci.yml` `env:` block).
3. Verify SES sender email (inbox confirmation link).
4. Request SES production access (AWS Support; can take days).
5. Request Bedrock model access for the configured model.
6. `aws ssm put-parameter --name /monitoring/ses/sender-email --type SecureString --value <addr>` — populates the one secret the operator owns (Terraform doesn't, to avoid plan-diff leakage).
7. First push to `main` triggers `terraform-apply` + `deploy-lambda` via OIDC.

## Smoke test on a real account

```bash
aws lambda invoke \
  --function-name monitoring-main:live \
  --payload '{"instrument_ids": ["<uuid>"]}' \
  /dev/null
```

## Stack-specific gotchas worth knowing

- `@TestPropertySource` is **Spring**; use `@Property(name=..., value=...)` or `@MicronautTest(propertySources = ...)`.
- `Instant.now()` and `UUID.randomUUID()` are banned in business code — inject `Clock` and `UuidGenerator`.
- The PR cadence in this repo is one small focused PR per scope; the user merges, then asks for the next. PR descriptions follow a "Summary / Change / Test plan / Out of scope" template.
- Spotless uses palantir-java-format; `mvn spotless:apply` before commit if `mvn verify` complains about formatting.
- `application-test.yml` sets `monitoring.email.sender-email = alerts@test.local` since `EmailConfig.senderEmail` is `@NotBlank` and has no default.

## Pointer for next-session pickup

If the user asks for follow-ups on this branch of work:

- Implementing the `mon` CLI per CLAUDE.md §10 manual-operations table is the most obvious next deliverable.
- A live smoke test on a real AWS account is the remaining unticked validation.
- Defense-in-depth retry-poller claim (deferred #4) and SNAPSHOT_ONLY atomicity (#6) are the two correctness items still on the table — both have detailed approaches in `/root/.claude/plans/tingly-gathering-snowflake.md`.
