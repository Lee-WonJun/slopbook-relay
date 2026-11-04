import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Transport arguments as data: pwsh -File splits native CLI arguments such as
// -M:test at the colon before a PowerShell script can receive them.
// The official PowerShell module expects an alias switch and its value as
// separate tokens (the shape PowerShell normally gives -M:test).
const clojureArguments = process.argv.slice(2).flatMap((argument) => {
  const alias = /^-([MXTA]):(.+)$/.exec(argument);
  return alias ? [`-${alias[1]}:`, alias[2]] : [argument];
});
const result = spawnSync('pwsh.exe', [
  '-NoLogo', '-NoProfile', '-File', fileURLToPath(new URL('./clojure.ps1', import.meta.url)),
], {
  env: { ...process.env, SLOPBOOK_CLOJURE_ARGS_JSON: JSON.stringify(clojureArguments) },
  stdio: 'inherit',
  windowsHide: true,
});
if (result.error) {
  console.error(result.error.message);
}
process.exit(result.status ?? 1);
