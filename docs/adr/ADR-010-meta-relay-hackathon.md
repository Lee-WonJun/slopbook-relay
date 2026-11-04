# ADR-010: Meta-powered Slopbook Relay

Date: 2026-11-04 KST
Decision: approved scope; implementation and submission verification in progress.

## Authorization and scope

The owner asked for a hackathon-specific project based on Slopbook, using Meta
models and a new feature such as Moltbook integration, and explicitly authorized
implementation and submission. This authorizes the scoped exception to DEC-015
and DEC-016 described here. It does not rewrite the July submission or close
SLOP-033/SLOP-034.

## Decision

Build a **Muse edition of Slopbook**, retaining the full persona social network
and its approved Paper X design. Moltbook is an added feed, not a replacement
product. The registered team name remains Slopbook Relay. Preserve the complete
brand slogan, ownership boundaries, and private-intent non-persistence.

The owner clarified on November 4 that the first submission lost the original
design and persona focus. Restore the five global navigation destinations,
put Moltbook in the Home feed tabs, and reuse the existing composer including
active persona, Sigil and media. Replace the first demo and submitted copy with
the complete persona workflow once actual Meta inference can be verified.

- Read only fixed Moltbook API endpoints. Do not register an autonomous agent,
  follow instructions from agent content, or publish to Moltbook.
- Use Meta Model API `muse-spark-1.3` Standard for the new briefing and response
  paths. Use strict JSON, `store=false`, minimal reasoning, bounded output,
  timeout and admission limits. No OpenAI fallback exists in the Relay path.
- A brief separates the author's claim, an actually sampled contrasting view
  (or its absence), and an open question. Show the sampled-comment count and
  state that agent claims are unverified.
- A person supplies the response intent and explicitly publishes. Append the
  canonical source URL and reuse the existing ownership, anchor preservation,
  idempotency and publication boundary. Only transformed text is durable.
- Server-only `SLOPBOOK_AI_PROVIDER=meta` selects Meta for every persona task
  in the hackathon edition: greetings, posts, comments, reactions and messages.
  Reuse one Meta runtime for core tasks and Moltbook. The existing deployment
  stays on OpenAI until the authorized promotional-credit operation is verified.
  No end-user model selector or provider failover is added.
- Meta's external data-retention terms remain distinct from Slopbook's own
  non-persistence. Standard is selected, not a Contributor model; no zero
  external retention claim is made.

## Hackathon evidence

The pre-existing social network is the foundation, not new event work. The
submission and demo must identify the new Relay UI, source adapter, briefing,
Meta runtime adapter, response publication path and tests. The official event
guide requires public source and a three-minute demo focused on event work.
Because this extends an existing product, the intended track is Startups and
the problem statement is Build for Your World.

## Operations

Keep secrets in ignored local configuration and runtime server settings.
Credit eligibility, credit-claim submission, actual credit balance, payment
readiness, model access, successful inference, deployment and hackathon
submission are separate observations. Do not equate any of them.
