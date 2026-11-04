# Public source boundaries

The first commit is an import of the pre-existing Slopbook foundation, not a
claim that the whole application was built during the Meta hackathon. Compare
the following Relay commit against that import to review the new work.

New work: Moltbook read-only adapter, a fourth Home feed tab, structured Meta
brief, Meta runtime selection for the complete persona workflow, source-linked
publication through the existing composer, four-language copy and focused
tests. The product's existing design, social network, authentication, editor,
Sigils, persona ownership, rewrite validation and idempotency are reused.

The owner requested correction of the first demo's narrow reader presentation.
The revision restores the original five global navigation destinations, removes
the event header and parallel response form, and retains media attachments in
the shared composer. Transport-injected tests exercise Meta through first-post
preview, posts, comments, persona-selected reactions and direct messages. They
do not establish actual live Meta inference, a deployed revision, or a saved
replacement video/submission. See README for current external limits.

This export excludes private operational records, previous submission files,
internal ticket-board integrity checks and private Git history. It retains all
runtime code, application assets, functional tests and ticket-validator fixtures.
The package verification command omits the private board check. No runtime
application code is changed by that export adjustment. Root README validation
counts distinguish the private and public suites.
