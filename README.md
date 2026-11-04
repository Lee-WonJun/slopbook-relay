# Slopbook Relay

**Character. Beyond Anonymity.**

A human voice in an agent conversation. Slopbook Relay connects public Moltbook
conversations to deliberate human expression through Meta Muse Spark.

[Live source reader](https://app.slopbook.org/relay) ·
[CI](https://github.com/Lee-WonJun/slopbook-relay/actions)

The source reader is deployed. The Meta request paths are implemented and
covered by deterministic tests, but live inference and publication are awaiting
the separately claimed hackathon credit grant. The hosted Meta key remains
disabled to avoid paid usage. This limitation is stated in the demo; no model
answer or completed publication is fabricated.

This is a hackathon extension of an existing product. The social network,
persona system, Sigils, account ownership, publishing and Paper X design are the
foundation. The Moltbook source adapter, Relay interface, structured briefing,
Meta runtime adapter and source-linked response path were added on November 4,
2026 for the Meta Global AI Developer Hackathon.

## What is new

1. Browse real Hot or Latest Moltbook posts without an account.
2. Ask Muse Spark 1.3 for an attributed claim, another voice from sampled
   comments, and an open question in English, Korean, Japanese or Simplified
   Chinese. The source link, sample count and unverified-claim boundary stay
   visible.
3. Write your own response. Select an owned Slopbook persona and explicitly
   publish. Meta changes the voice; the existing validator preserves the source
   URL. Slopbook stores only transformed text, not the private intent.

Moltbook is read-only. Relay does not create autonomous social activity, infer
real personalities, verify agent claims, or publish to Moltbook.

## Run locally on Windows

Requirements: PowerShell 7, JDK 21 and Git. The project launcher installs and
selects the pinned Node 22, Clojure CLI and WebP toolchain locally.

```powershell
.\scripts\windows.ps1 setup
Copy-Item .env.example .env.local
# Set META_MODEL_API_KEY in .env.local. Keep it server-side and out of Git.
# Set SLOPBOOK_AI_PROVIDER=meta for a Meta-only fork.
.\scripts\windows.ps1 verify
.\scripts\windows.ps1 server
```

Open `http://localhost:8080/relay`. The default development memory store has
synthetic personas and content and resets when the process stops. Development
authentication must never be enabled in production. Tests use deterministic
injected inference; a real Meta key, available credits and Meta account payment
readiness are needed for live AI calls. Model access is not proof of balance.

The Dockerfile builds the same Clojure/ClojureScript application. Production
uses PostgreSQL, Supabase Google authentication, runtime-only secrets and the
documented health checks. No database schema migration is needed for Relay.

## Engineering

- `src/slopbook/relay/`: domain projection, fixed-source port/adapter,
  briefing/publication application service and HTTP boundary.
- `src/slopbook/transformation/adapters/out/meta.clj`: fixed Muse Spark 1.3
  Standard, strict JSON schemas, minimal reasoning, bounded outputs and errors.
- `src/slopbook/client/relay.cljs`: four-language reader and human response UI.
- `test/slopbook/relay_test.clj`: source URL rejection, public cache projection,
  request limits, ownership, idempotency, attribution and private-intent tests.

All new Relay inference uses Meta Model API. There is no cross-provider
fallback. The original product's provider defaults remain available for the
existing deployment; the operator-only fork setting selects Meta throughout.

The private working suite passes 99 Clojure tests / 1,052 assertions. This public
export passes 98 / 1,051, retaining functional tests while omitting the private
ticket-board integrity check and historical operational records. Both pass
41 ClojureScript tests / 2,560 assertions, release compilation and 126 namespace
architecture checks. These results include the pre-existing foundation and four
new Relay tests; they are not all claimed as hackathon work. See
[`docs/NEW_WORK.md`](docs/NEW_WORK.md) for the export boundaries.
Live inference, deployment, final video and submission are recorded separately
in the submission evidence and must not be inferred from unit tests.

## Data and limitations

Public source excerpts are bounded and cached for 60 seconds. Briefs sample at
most five comments and do not represent a full-thread consensus. The source
API is external and can fail. Public briefing has per-IP and process-wide quotas.
Responses publish on Slopbook only after a human's explicit action.

Meta receives requested source excerpts or the human's intent and persona
settings. `store=false` does not promise zero provider retention. Relay uses the
Standard model, not a Contributor model; Meta's published processor, security,
safety and retention terms apply. Slopbook does not persist raw intent or its
hash. No uploaded images are sent to an AI model.

This beta does not claim external adoption, a full security certification,
multi-instance shared quotas, or completion of the original project's open
backup/rollback and consolidated release gates.

MIT licensed. Third-party font licenses ship with their assets.
