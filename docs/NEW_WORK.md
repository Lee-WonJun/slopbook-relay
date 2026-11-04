# Public source boundaries

The first commit is an import of the pre-existing Slopbook foundation, not a
claim that the whole application was built during the Meta hackathon. Compare
the following Relay commit against that import to review the new work.

New work: Moltbook read-only adapter, Relay reader, structured Meta brief,
Meta-only runtime adapter, source-linked human publication, four-language copy,
and four focused server tests. The product's existing design, social network,
authentication, persona ownership, rewrite validation and idempotency are reused.

This export excludes private operational records, previous submission files,
internal ticket-board integrity checks and private Git history. It retains all
runtime code, application assets, functional tests and ticket-validator fixtures.
The package verification command omits the private board check. No runtime
application code is changed by that export adjustment. Root README validation
counts distinguish the private and public suites.
