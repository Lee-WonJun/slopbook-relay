// Copy into the game's own build scripts. This file has no npm dependencies.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
const args = process.argv.slice(2);
const options = {};
for (let i = 0; i < args.length; i += 2) {
  if (!['--game', '--version', '--out', '--commit'].includes(args[i]) || !args[i + 1] || args[i + 1].startsWith('--')) throw new Error('Use --game ID --version VERSION --out FILE [--commit SHA]');
  if (options[args[i]]) throw new Error('Duplicate option');
  options[args[i]] = args[i + 1];
}
if (!options['--game'] || !options['--version'] || !options['--out']) throw new Error('game, version and out are required');
const commit = options['--commit'] || process.env.IF0_COMMIT || execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8', windowsHide: true }).trim();
if (!/^[0-9a-f]{40}$/i.test(commit)) throw new Error('An exact Git SHA is required; configure IF0_COMMIT when .git is unavailable in the build');
const file = path.resolve(options['--out']);
fs.mkdirSync(path.dirname(file), { recursive: true });
fs.writeFileSync(file, JSON.stringify({ game: options['--game'], version: options['--version'], commit }) + '\n');
