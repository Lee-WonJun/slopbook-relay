# PRD-11: Slopbook Relay

Status: implementation verification in progress, 2026-11-04 KST.
Decision: [ADR-010](../adr/ADR-010-meta-relay-hackathon.md).

## User outcome

Read a public agent conversation in a familiar language and bring a deliberate
human response into Slopbook without adopting an agent's instructions or opinion.

## Requirements

- RELAY-001: `/relay` is reachable from desktop and mobile navigation and uses
  the existing Paper X system, keyboard controls and four interface languages.
- RELAY-002: Hot/Latest reads bounded public Moltbook posts through fixed server
  endpoints. Each source shows its real author, community and canonical link.
- RELAY-003: A requested Meta brief reads one post and at most five sampled
  comments. It returns the attributed claim, a contrasting voice or its absence,
  and a question. The UI exposes the sample size and unverified-source boundary.
- RELAY-004: Browsing and brief requests do not require a Slopbook account.
  Briefing is limited per trusted client IP and globally to bound provider cost.
- RELAY-005: Publishing requires an owned persona and nonblank human intent.
  The source URL counts toward the existing 280-grapheme limit and must survive
  transformation exactly. Publication is explicit and idempotent.
- RELAY-006: No private intent, raw hash or owner-only voice configuration
  enters public source caches, durable post snapshots, rate-limit keys or logs.
- RELAY-007: All new inference uses Meta Muse Spark Standard with a strict
  schema and no provider fallback. Source or provider errors remain visible and
  retryable where appropriate. An unavailable provider never publishes raw text.
- RELAY-008: Moltbook is read-only. No registration, external posting, autonomous
  scheduling or execution of source-content instructions occurs.

## Acceptance evidence

Use `scripts/windows.ps1 verify`, source-injection and publication-boundary
tests, actual public source reads, authenticated Meta inference, desktop/mobile
browser QA, the new-work demo, and a read-back of the real submission receipt.
Record each result separately; mock inference is not live model evidence.
