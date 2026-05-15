# Session retrospective — Heikin Ashi Monitoring Service

A step-by-step diary of one working session, plus learnings framed to
improve future agent instructions and `CLAUDE.md` files. Shareable as-is.

## 1. What we did, in order

| # | PR | Change | Layer | Triggered by |
|---|----|--------|-------|--------------|
| 1 | — | DEPLOY.md: clearer 2-instrument `force_email` example | docs | user request |
| 2 | #41 | `LatestBarEventFilter` — emit alerts only for the most recent HA bar per (instrument, timeframe) | application logic | user noticed bootstrap would emit dozens of stale historical alerts |
| 3 | #42 | Split CI `aws-preflight` into pre/post `terraform-apply` jobs | CI / ops | chicken-and-egg: IAM simulator gated apply, but apply was what fixed IAM |
| 4 | #43 | Rewrite IAM simulator step with real error surfacing (stop swallowing stderr) | CI / ops | simulator failed with opaque "ERROR" |
| 5 | #44 | Simulate `bedrock:InvokeModel` and `Converse` in separate calls | CI / ops | AWS API rejects both actions in one request |
| 6 | #45 | Pass `MONITORING_SES_REGION` env var to both Lambdas | infra wiring | SES called in wrong region → `UnrecognizedClientException` |
| 7 | #46 | Grant SES IAM on the domain identity, not just the email identity | infra / IAM | `AccessDenied` on `identity/<domain>` |
| 8 | #47 | Grant SES IAM on `configuration-set/*` | infra / IAM | `AccessDenied` on the default configuration-set |
| 9 | #48 | `XDG_CACHE_HOME=/tmp` to silence fontconfig warnings | infra | cosmetic log noise |
| 10 | #49 | Chart price axis: `setAutoRangeIncludesZero(false)` | feature / chart | candles squeezed into top of plot |
| 11 | #50 | Redesign HTML email as "quiet terminal" theme + readable summary table | feature / UI | user supplied a design |

End state: `force_email` smoke test sent 6 real emails (chart + Bedrock AI
note + SES delivery) end-to-end.

## 2. The shape of the work

Of 11 changes, **3 were application logic / features** (#41, #49, #50)
and **8 were infrastructure, IAM, CI, or wiring** (#42–#48). The features
were quick and went right the first time. The infrastructure work was a
cascade: each fix revealed the next failure one layer down.

The SES cascade is the clearest example — four sequential PRs, each
unblocking the next error:

1. Wrong region (env var never wired) → `UnrecognizedClientException`
2. Right region, but IAM only covered the email identity → `AccessDenied` on the domain identity
3. Domain identity granted, but a default configuration-set also needs a grant → `AccessDenied` on the configuration-set
4. Then it worked.

None of these were visible until runtime. None were knowable from the
code or the spec.

## 3. What went well

- **Application-logic changes were clean.** `LatestBarEventFilter` was
  specified in conversation, implemented as a pure service-layer helper,
  unit-tested 6 ways, shipped. The chart axis fix was one line. These map
  onto things that are *testable in-process*.
- **Diagnostics-first on the opaque CI failure.** When the IAM simulator
  failed with a meaningless "ERROR", the right move was to fix the
  diagnostic (#43) before guessing at causes — and it immediately
  revealed the real AWS API error.
- **Reading the design export properly.** The email redesign was done by
  parsing the 1 MB design-canvas HTML for exact fonts/colors/metrics,
  not eyeballing the screenshot — which corrected three wrong guesses
  (serif vs Helvetica, SF Mono vs Courier, chart border).

## 4. What went wrong (process)

- **Did not check PR merge status before pushing follow-up commits.**
  Pushed a fix onto an already-merged branch; the commit was orphaned
  and had to be cherry-picked onto a fresh branch. Rule learned: after
  any merge, re-branch from updated `main`; never assume a branch is
  still open.
- **Dismissed the supplied HTML file at first.** Saw "1 MB of computed
  styles", judged it unusable, and went to reverse-engineer from the
  screenshot. The file *was* the spec — it just needed parsing. Rule
  learned: a provided artifact is an instruction, not noise; analyze it
  before deciding it doesn't help.
- **Multiple round-trips on the SES cascade.** Each fix needed a deploy +
  manual re-test before the next error surfaced. Unavoidable in part
  (runtime-only failures), but a pre-flight checklist would have caught
  the region + IAM gaps in one pass instead of three.

## 5. The GWT-spec question

`CLAUDE.md` is written as Given/When/Then behavioral specs. Honest
assessment of how much they helped, per change type:

| Change type | GWT spec helped? | Why |
|-------------|-----------------|-----|
| Application logic (#41, detector filter) | ~90% | Behavior is testable in-process; the scenario *is* the implementation contract |
| Pure feature (#49 chart, #50 email) | ~70% | Visual intent doesn't fit GWT, but "where it lives" and "what data it has" did |
| Infra wiring (#45 env var) | ~10% | Config-vs-env mismatch is invisible to behavioral specs |
| IAM / SES (#46, #47) | ~5% | Runtime AWS authorization is not a behavior you can write a Given/When/Then for |
| CI workflow (#42, #43, #44) | 0% | Job ordering and tool quirks are pure ops |

**Conclusion:** GWT is excellent for the domain and application layers —
anything deterministic and testable without a network. It does nothing
for infrastructure glue or runtime-only failures. The session's pain was
almost entirely in the second category, and a behavioral spec format
cannot, by construction, prevent it.

## 6. Recommendations for future CLAUDE.md / instructions

GWT covers behavior. The gap is *procedures* and *non-functional
invariants*. Suggested additions:

### 6a. An "AWS service addition" checklist (procedure, not GWT)

Every time the code calls a new AWS API or a new resource, verify in one
pass — before deploying:

1. **IAM action** granted on the **exact resource ARN** — and check
   whether the service authorizes against a *broader* resource (SES:
   domain identity + configuration-set, not just the email identity).
2. **Region**: the SDK client's region must match where the resource
   actually lives. Confirm the config default *and* the env-var override.
3. **Env var wiring**: every `@ConfigurationProperties` value that
   differs from its default in production must be passed through in
   `lambda.tf` (or equivalent). A YAML default silently masks a missing
   env var.
4. **Filesystem**: Lambda is read-only except `/tmp`. Anything that
   writes a cache (AWT/fontconfig, etc.) needs redirecting.

This checklist would have collapsed PRs #45–#48 into one.

### 6b. A config-vs-environment invariant

State explicitly: "For every config property, the production value path
is `application.yml` default → env var override → `lambda.tf`. Adding a
property is incomplete until all three are aligned." This is the single
rule that would have prevented the SES-region bug.

### 6c. A CI-ordering principle

"A preflight check may only verify preconditions the pipeline cannot
itself fix. Anything the pipeline *creates or updates* must be verified
*after* the step that creates it." This is the #42 lesson, generalized.

### 6d. Process rules for the agent

- After any merge, re-branch from freshly pulled `main`. Never push onto
  a branch without confirming it is still open.
- A user-supplied artifact (design file, log, spec) is an input to
  analyze, not noise to skip — parse it before judging its usefulness.
- For opaque tool failures, fix the diagnostic before guessing the
  cause.

### 6e. Keep GWT where it works

Don't try to force infrastructure into Given/When/Then. Keep the
behavioral specs for `domain` and `application`; add a separate
"Operations & Infrastructure invariants" section for the checklist-style
rules above. They are different kinds of knowledge and a single format
serves neither well.

## 7. One-line summary

GWT specs made the logic changes fast and correct; the session's real
cost was infrastructure and runtime-authorization failures that no
behavioral spec can describe — so the highest-leverage `CLAUDE.md`
improvement is a procedural pre-flight checklist for AWS/IAM/config
wiring, kept separate from the GWT behavioral specs.
