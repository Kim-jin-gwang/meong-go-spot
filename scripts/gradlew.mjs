// Cross-platform Gradle wrapper launcher for root npm scripts.
// `cd <module> && gradlew` breaks depending on the npm script shell,
// so resolve the right wrapper per platform here instead.
//
// 사용: node scripts/gradlew.mjs <backend|android> <gradle tasks...>
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const MODULES = ['backend', 'android'];
const [module, ...tasks] = process.argv.slice(2);
if (!MODULES.includes(module)) {
  console.error(`first argument must be one of: ${MODULES.join(', ')}`);
  process.exit(1);
}

const moduleDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', module);
const isWindows = process.platform === 'win32';
const wrapper = path.join(moduleDir, isWindows ? 'gradlew.bat' : 'gradlew');

const result = spawnSync(wrapper, tasks, {
  cwd: moduleDir,
  stdio: 'inherit',
  shell: isWindows,
});

process.exit(result.status ?? 1);
