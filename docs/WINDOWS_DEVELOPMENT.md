# Windows development

The active checkout is `F:\Project\own\slopbook`. Use PowerShell 7 and JDK 21.
The launcher installs Node 22.23.4, Clojure CLI 1.12.0.1530 (matching CI), and
WebP 1.6.0 into the Git-ignored `.tools` directory. Download URLs and SHA-256
hashes are pinned in `scripts/windows/toolchain.json`. Application dependency
versions remain defined by `deps.edn` and `package-lock.json`.

```powershell
Set-Location F:\Project\own\slopbook
.\scripts\windows.ps1 setup
.\scripts\windows.ps1 verify
```

`setup` reuses installed tools and runs `npm ci`. JDK selection uses
`.tools/windows.local.json` (`{"javaHome":"C:/path/to/jdk-21"}`), then `JAVA_HOME`,
then `java.exe` on PATH. It requires Java 21 and changes only the current
PowerShell process environment. System PATH and PowerShell profiles are unchanged.

## Run the app

Build the browser assets once, then start the backend:

```powershell
.\scripts\windows.ps1 npm run cljs:release
.\scripts\windows.ps1 server
```

Open <http://localhost:8080>. For automatic ClojureScript compilation, run
`.\scripts\windows.ps1 dev` in another terminal and continue using the backend
URL on port 8080. Refresh the browser after a change. The watch build uses
external script loading to respect the application's existing CSP. The
eval-based browser REPL and automatic code reload are disabled. Port 8081 serves
static files for shadow-cljs; application requests need the backend. Stop either
foreground process with Ctrl+C.

The default development mode uses an in-memory store and development auth.
It does not need PostgreSQL, Supabase, or WSL. Data disappears when the server
stops. AI writes require a server process environment containing your own
`OPENAI_API_KEY`; without a key, public reads work and AI writes report
their unavailable state. The `server` command reads ignored `.env.local` as literal environment data; existing process variables take precedence. Other commands do not load it. The launcher does not contact production services to obtain credentials.

```powershell
$env:OPENAI_API_KEY = '<your-server-only-key>'
$env:SLOPBOOK_AI_DISCOVERY = 'true'
.\scripts\windows.ps1 server
```

Use `.env.example` as a reference for optional environment variables. Keep real
values out of source, command transcripts, and commits. Local ports can be
changed with `$env:SLOPBOOK_PORT` and `$env:SLOPBOOK_PUBLIC_BASE_URL`.

## Checks and build

```powershell
.\scripts\windows.ps1 verify
.\scripts\windows.ps1 build
.\scripts\windows.ps1 clojure -M:architecture
.\scripts\windows.ps1 clojure -M:tickets check
```

`verify` runs the existing Clojure and ClojureScript tests, browser release
build, namespace architecture checks, and ticket validation. `build` produces
`target/slopbook-0.2.0-standalone.jar`. Run arbitrary existing npm commands with
`.\scripts\windows.ps1 npm <arguments>`. For a terminal where plain npm commands
use this toolchain, first run `.\scripts\windows.ps1 env`.

PostgreSQL integration checks additionally need a disposable local PostgreSQL
database. This Windows checkout has native PostgreSQL 15.19 tools in
`.tools/pgsql` and an isolated test cluster in `.dev/postgres-data-15`. Start it,
run the tests, and stop it with:

```powershell
& .\.tools\pgsql\bin\pg_ctl.exe -D .dev/postgres-data-15 -l .dev/postgres.log -o '-h 127.0.0.1 -p 15432' -w start
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:15432/slopbook?user=slopbook&password=local-slopbook-only'
.\scripts\windows.ps1 npm run test:integration
& .\.tools\pgsql\bin\pg_ctl.exe -D .dev/postgres-data-15 -m fast -w stop
```

These tools came from the [EDB Windows binary archive](https://www.enterprisedb.com/download-postgresql-binaries),
version `15.19-5`, with SHA-256
`49e0690194d9fe2bb4d7d6587fdddaa85a3eaaafe2fd5480e0916fcb25d0143e`.
Only the server binaries, libraries, shared data, and licenses were extracted;
no Windows service was installed. The cluster binds to `127.0.0.1` and uses the
sample local-only password above. It contains test fixtures, not migrated user
or production data. It is stopped after migration validation.

Alternatively, with Docker Desktop running, use the existing `compose.yaml`
instead of the native cluster (both use port 15432):

```powershell
docker compose up -d postgres
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:15432/slopbook?user=slopbook&password=local-slopbook-only'
.\scripts\windows.ps1 npm run test:integration
```

The compose database uses tmpfs and is disposable. The full PostgreSQL server
mode also requires the Supabase settings documented in the root README.

## Migration record

On 2026-10-29, 2,864 source, Git, and working-material files (129,925,984 bytes)
were copied from `/home/dldnjs1013/project/slopbook` in the Ubuntu WSL distro.
Every copied file matched its source SHA-256 hash. The starting commit was
`8f4021d910155767a49d758a454fb36e690bbc06`, and both working trees were clean
before the Windows support files were added. Git history and remote access
were verified from Windows. Repository-local `core.filemode=false` and
`core.autocrlf=false` preserve the source file contents.

Linux dependencies and build caches were excluded and rebuilt on Windows.
No `.env` or `.env.local` existed in the source checkout. The WSL original
remains as a recovery copy; continue development in the Windows checkout.
The detailed file hash manifest is stored outside the repository at
`%LOCALAPPDATA%\SlopbookMigration\2026-10-29\copy-manifest.json`.

## Validation on Windows, 2026-10-29

| Check | Result |
|---|---|
| Clojure suite | 92 tests, 932 assertions, 0 failures/errors |
| ClojureScript suite | 41 tests, 2,514 assertions, 0 failures/errors |
| PostgreSQL 15.19 integration suite | 24 tests, 209 assertions, 0 failures/errors |
| Namespace architecture | 120 namespaces, 0 violations |
| Ticket validation | 47 tickets, 0 errors |
| Release and standalone build | `npm run verify` and `npm run uberjar` passed |
| Standalone runtime | JVM 21 JAR on port 18080 returned ready, HTML, and bundled JavaScript |
| Native web runtime | 13 health, API, asset, and policy routes returned HTTP 200 on port 8080 |
| Image pipeline | PNG multipart upload reached `ready` with native Windows WebP processing |
| Browser | Feed, Korean text, and Sigil images loaded without new console errors |
| Native file watching | Touching an unchanged source triggered a successful incremental build |
| Git | Object integrity and authenticated origin HEAD lookup passed |
| Source preservation | All 614 copied working-material files matched except the four intended existing-file edits |

The existing-file edits are `.gitignore`, `.dockerignore`, `README.md`, and
`shadow-cljs.edn`; Windows launchers, this guide, and `AGENTS.md` are additions.
Application dependency versions and production security headers were retained.
Initial builds emitted existing third-party diagnostics in Supabase's Phoenix
bundle; the compiled release and local application both ran successfully.

Validation logs and runtime smoke results are in the ignored `.dev` directory.
The old WSL checkout remained clean. Before the separate OpenAI migration, AI provider inference and live Supabase
OAuth/storage were not exercised because this checkout had no local credentials.
This development migration does not change SLOP-033/SLOP-034 or production
release acceptance status.
