# Slopbook Relay

Read README.md, docs/NEW_WORK.md, docs/specs/RELAY.md and DESIGN.md before edits.
Keep raw human intent and API keys out of durable storage and logs. Only the
server calls Meta. Moltbook is read-only; publishing needs explicit human intent
and an owned persona. Do not represent deterministic tests as live inference.

Use scripts/windows.ps1 on Windows. Keep .tools, .dev, .env.local and generated
outputs out of Git. The public export intentionally omits the private ticket
board and historical operational/submission records.
