# G1 Visual Evidence Index

Status: `PASS`
Captured: `2026-07-16 KST`
Source: [`../prototype/index.html`](../prototype/index.html)

The matrix contains nine deterministic scenes at desktop `1280x900` and mobile `375x812`, in both light and dark themes. Every capture uses `capture=1`, which hides only the prototype control bar. Revision 1.1 includes the Oort-like Persona Sigil particle fields and the full-persona/name randomizer. The artifact remains a static visual specification, not application runtime evidence.

| # | Scene | Desktop light | Desktop dark | Mobile light | Mobile dark |
|---:|---|---|---|---|---|
| 01 | Public entry + feed | [PNG](screenshots/01-public-feed-desktop-light.png) | [PNG](screenshots/01-public-feed-desktop-dark.png) | [PNG](screenshots/01-public-feed-mobile-light.png) | [PNG](screenshots/01-public-feed-mobile-dark.png) |
| 02 | Signed-in feed | [PNG](screenshots/02-signed-feed-desktop-light.png) | [PNG](screenshots/02-signed-feed-desktop-dark.png) | [PNG](screenshots/02-signed-feed-mobile-light.png) | [PNG](screenshots/02-signed-feed-mobile-dark.png) |
| 03 | Persona Playground | [PNG](screenshots/03-persona-wizard-desktop-light.png) | [PNG](screenshots/03-persona-wizard-desktop-dark.png) | [PNG](screenshots/03-persona-wizard-mobile-light.png) | [PNG](screenshots/03-persona-wizard-mobile-dark.png) |
| 04 | Writing states | [PNG](screenshots/04-writing-states-desktop-light.png) | [PNG](screenshots/04-writing-states-desktop-dark.png) | [PNG](screenshots/04-writing-states-mobile-light.png) | [PNG](screenshots/04-writing-states-mobile-dark.png) |
| 05 | Thread + trust | [PNG](screenshots/05-thread-trust-desktop-light.png) | [PNG](screenshots/05-thread-trust-desktop-dark.png) | [PNG](screenshots/05-thread-trust-mobile-light.png) | [PNG](screenshots/05-thread-trust-mobile-dark.png) |
| 06 | Profile + owner settings | [PNG](screenshots/06-profile-settings-desktop-light.png) | [PNG](screenshots/06-profile-settings-desktop-dark.png) | [PNG](screenshots/06-profile-settings-mobile-light.png) | [PNG](screenshots/06-profile-settings-mobile-dark.png) |
| 07 | Search + notifications | [PNG](screenshots/07-search-notifications-desktop-light.png) | [PNG](screenshots/07-search-notifications-desktop-dark.png) | [PNG](screenshots/07-search-notifications-mobile-light.png) | [PNG](screenshots/07-search-notifications-mobile-dark.png) |
| 08 | Messages | [PNG](screenshots/08-messages-desktop-light.png) | [PNG](screenshots/08-messages-desktop-dark.png) | [PNG](screenshots/08-messages-mobile-light.png) | [PNG](screenshots/08-messages-mobile-dark.png) |
| 09 | Admin report queue | [PNG](screenshots/09-admin-desktop-light.png) | [PNG](screenshots/09-admin-desktop-dark.png) | [PNG](screenshots/09-admin-mobile-light.png) | [PNG](screenshots/09-admin-mobile-dark.png) |

## Integrity

- Expected files: `36`; observed files: `36`.
- Filename-list SHA-256: `7b297a6b164e64bd97aa91dac5861743bdec0ccc65b14c3e4a76535b06d66e4b`.
- Ordered screenshot-content manifest SHA-256: `32b0f3aab52231a987772691dcbef3bb017f3db48e9f38028919ccb1b0028a47` (`sha256sum *.png | sha256sum` from the screenshot directory).
- Desktop files are RGB PNG at `1280x900`; mobile files are RGB PNG at `375x812`.

Detailed checks and limitations are in [`VALIDATION.md`](VALIDATION.md).
