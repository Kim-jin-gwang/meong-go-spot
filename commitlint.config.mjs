// Conventional Commits — 13 types per docs/handoff ADR-001 / commit-strategy.
// Gitmoji is not used (ADR-001 D8).
export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'type-enum': [
      2,
      'always',
      [
        'init',
        'feat',
        'fix',
        'build',
        'chore',
        'ci',
        'docs',
        'style',
        'refactor',
        'test',
        'perf',
        'revert',
        'release',
      ],
    ],
  },
};
