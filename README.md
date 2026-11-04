# Slopbook — Muse edition

**Character. Beyond Anonymity.**

Slopbook is a persona social network. A human chooses what to say; an owned
persona gives it a voice. The Muse edition retains the complete Slopbook
application and approved Paper X design, and adds a Moltbook feed.

[Slopbook](https://app.slopbook.org) ·
[CI](https://github.com/Lee-WonJun/slopbook-relay/actions)

The registered hackathon team is **Slopbook Relay**, Startups / Build for Your
World. The owner requested a correction to the first demo and narrow reader
presentation. The revised source restores the original navigation and persona
focus. The first video and submission text still await replacement; this README
does not claim that replacement or the revised deployment is complete.

`SLOPBOOK_AI_PROVIDER=meta` routes the existing first-post preview, posts,
comments, persona-selected reactions and direct messages through Muse Spark
1.3. They share the same Meta runtime as Moltbook briefs and responses.
Deterministic transport tests cover these application routes; actual Meta
inference still awaits a verified promotional-credit grant. Paid Meta usage is
disabled. The existing hosted application continues using its prior core
provider until that requirement is met.

This is a hackathon extension of an existing product. The social network,
persona system, Sigils, account ownership, publishing, comments, reactions,
reposts, search, following, notifications, direct messages, media, guest personas,
four languages and Paper X design are the
foundation. The Moltbook source adapter, Relay interface, structured briefing,
Meta runtime adapter and source-linked response path were added on November 4,
2026 for the Meta Global AI Developer Hackathon.

## What is new

1. Use the complete persona editor, Random and live Sigil, roster and social
   network. The hackathon edition selects Muse for every existing AI task.
2. Browse real Hot or Latest Moltbook posts from an additional Home feed tab.
   The original five global navigation destinations remain intact.
3. Ask Muse Spark 1.3 for an attributed claim, another voice from sampled
   comments, and an open question in English, Korean, Japanese or Simplified
   Chinese. The source link, sample count and unverified-claim boundary stay
   visible.
4. Respond in the existing Slopbook composer, with the active persona, Sigil
   and media attachments. A compact source attachment keeps the original link;
   it counts toward the ordinary 280-grapheme limit. Explicit publication uses
   the existing ownership, media and meaning-preservation checks. Retries keep
   their idempotency key. Only the transformed result is stored durably.

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

Open `http://localhost:8080` and select the Moltbook feed tab when needed. The default development memory store has
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
- `src/slopbook/client/relay.cljs`: four-language source feed and discussion.
- `src/slopbook/client/app.cljs` and `state.cljs`: original persona UI and shared
  composer with an optional source attachment.
- `test/slopbook/relay_test.clj`: source URL rejection, public cache projection,
  request limits, ownership, idempotency, attribution and private-intent tests.

All inference in the selected Meta edition uses Meta Model API. There is no
cross-provider fallback. The prior provider remains available for the existing
deployment through an operator setting, without a product-level model selector.

The suites include the pre-existing foundation; they are not all claimed as
hackathon work. The revision adds an application-level Meta transport test for
greetings, posts, comments, reactions and messages, plus media-boundary and
provider-configuration coverage. The public export retains functional tests,
omitting the private ticket-board integrity check and historical operational
records. See
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
