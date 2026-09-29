// .ai/skills/*/SKILL.md 원본에서 툴별 포인터 스텁을 생성한다.
// 스킬을 추가·수정하면 이 스크립트를 다시 실행해 스텁을 동기화한다:
//   node scripts/generate-skill-stubs.mjs
//
// 스텁의 frontmatter(name, description)는 원본에서 복제한다 — 툴의 스킬
// 탐색·자동 트리거가 description을 읽기 때문이다. 본문은 원본 위임만 한다.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const canonicalDir = path.join(root, '.ai', 'skills');
const targets = ['.claude', '.agents'];

const skills = fs
  .readdirSync(canonicalDir, { withFileTypes: true })
  .filter((e) => e.isDirectory())
  .map((e) => e.name);

for (const skill of skills) {
  const canonicalPath = path.join(canonicalDir, skill, 'SKILL.md');
  const source = fs.readFileSync(canonicalPath, 'utf8');
  const match = source.match(/^---\r?\n([\s\S]*?)\r?\n---/);
  if (!match) {
    console.error(`SKIP ${skill}: no frontmatter found in ${canonicalPath}`);
    process.exitCode = 1;
    continue;
  }

  const stub = [
    '---',
    match[1],
    '---',
    '',
    `Read and follow the canonical project skill at \`../../../.ai/skills/${skill}/SKILL.md\`.`,
    'Treat that file as the source of truth. If it cannot be read, report the skill as blocked instead of inventing project rules.',
    '',
  ].join('\n');

  for (const target of targets) {
    const stubDir = path.join(root, target, 'skills', skill);
    fs.mkdirSync(stubDir, { recursive: true });
    fs.writeFileSync(path.join(stubDir, 'SKILL.md'), stub);
    console.log(`wrote ${path.join(target, 'skills', skill, 'SKILL.md')}`);
  }
}
