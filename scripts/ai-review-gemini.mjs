// Gemini AI 코드 리뷰 — GitLab CI MR 파이프라인에서 실행된다.
// 이전 프로젝트의 GitHub Actions 구현(ai-review-gemini.yml)을 GitLab API로 이식.
//
// non-blocking 원칙: 이 스크립트는 어떤 실패에서도 exit 0으로 끝난다.
// AI 리뷰는 참고 정보이며 머지를 막지 않는다 (CI 잡의 allow_failure와 이중 안전장치).
//
// 필요 변수 (GitLab Settings → CI/CD → Variables):
//   GEMINI_API_KEY       필수. 없으면 경고 후 스킵.
//   GITLAB_TOKEN         필수. api scope의 project access token (코멘트 작성용).
//   GEMINI_REVIEW_MODEL  선택. 기본 gemini-2.5-flash.
import fs from 'node:fs';
import path from 'node:path';

const MARKER = '<!-- ai-review-gemini -->';
const MAX_FILES = 30;
const MAX_DIFF_CHARS = 60000;
const MAX_DOCS = 20;
const MAX_DOC_CHARS = 8000;
const MAX_DOCS_TOTAL_CHARS = 40000;
const IMAGE_RE = /\.(png|jpe?g|gif|svg|webp|ico)$/i;

function warnAndExit(message) {
  console.warn(`[ai-review] ${message}`);
  process.exit(0);
}

const apiKey = process.env.GEMINI_API_KEY;
const gitlabToken = process.env.GITLAB_TOKEN;
// gemini-2.5-flash는 신규 발급 키에서 404 (2026-08 확인). 모델 교체는 GEMINI_REVIEW_MODEL 변수로도 가능.
const model = process.env.GEMINI_REVIEW_MODEL || 'gemini-3.6-flash';
const apiBase = process.env.CI_API_V4_URL;
const projectId = process.env.CI_PROJECT_ID;
const mrIid = process.env.CI_MERGE_REQUEST_IID;
const workspace = process.env.CI_PROJECT_DIR || process.cwd();

if (!apiKey) warnAndExit('GEMINI_API_KEY is not configured. Skipping review.');
if (!gitlabToken) warnAndExit('GITLAB_TOKEN is not configured. Skipping review.');
if (!apiBase || !projectId || !mrIid) {
  warnAndExit('Not running in a GitLab MR pipeline (CI variables missing). Skipping review.');
}

const mrApi = `${apiBase}/projects/${projectId}/merge_requests/${mrIid}`;
const gitlabHeaders = { 'PRIVATE-TOKEN': gitlabToken, 'Content-Type': 'application/json' };

async function gitlab(url, options = {}) {
  const response = await fetch(url, { headers: gitlabHeaders, ...options });
  if (!response.ok) {
    throw new Error(`GitLab API ${options.method || 'GET'} ${url} -> ${response.status} ${await response.text()}`);
  }
  return response.json();
}

function collectDocs(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .flatMap((entry) => {
      const fullPath = path.join(dir, entry.name);
      if (entry.isDirectory()) return collectDocs(fullPath);
      if (!entry.isFile() || !/\.(md|mdx|txt)$/i.test(entry.name)) return [];
      return [fullPath];
    });
}

try {
  const mr = await gitlab(mrApi);
  const changes = await gitlab(`${mrApi}/changes`);

  const reviewableFiles = (changes.changes || [])
    .filter((file) => !file.deleted_file)
    .filter((file) => file.diff)
    .filter((file) => !IMAGE_RE.test(file.new_path || ''))
    .slice(0, MAX_FILES);

  if (reviewableFiles.length === 0) {
    warnAndExit('No reviewable text diff found.');
  }

  const diff = reviewableFiles
    .map((file) =>
      [
        `File: ${file.new_path}`,
        `Status: ${file.new_file ? 'added' : file.renamed_file ? 'renamed' : 'modified'}`,
        'Patch:',
        file.diff,
      ].join('\n'),
    )
    .join('\n\n---\n\n')
    .slice(0, MAX_DIFF_CHARS);

  const docsContext = collectDocs(path.join(workspace, 'docs'))
    .sort()
    .slice(0, MAX_DOCS)
    .map((filePath) => {
      const relativePath = path.relative(workspace, filePath);
      const content = fs.readFileSync(filePath, 'utf8').slice(0, MAX_DOC_CHARS);
      return `Document: ${relativePath}\n${content}`;
    })
    .join('\n\n---\n\n')
    .slice(0, MAX_DOCS_TOTAL_CHARS);

  const prompt = `
You are reviewing a GitLab merge request.

Review goals:
- Find correctness bugs, security risks, missing validation, broken edge cases, and test gaps.
- Read the docs context and check whether the diff follows the documented project rules, conventions, and requirements.
- When a finding depends on documentation, cite the relevant docs path.
- Prioritize actionable issues over style preferences.
- Do not invent problems when the diff does not provide enough evidence.
- Write the review in Korean.
- Keep the review concise.

Response format:
## Gemini Code Review
### Summary
- ...

### Findings
- [severity] file:line - issue and suggested fix

### Test Suggestions
- ...

If there are no meaningful findings, say so clearly and only mention residual risks.

Merge request title:
${mr.title}

Merge request description:
${mr.description || '(empty)'}

Docs context:
${docsContext || '(no docs found)'}

Diff:
${diff}
`;

  const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`;
  const geminiResponse = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: prompt }] }] }),
  });

  if (!geminiResponse.ok) {
    warnAndExit(`Gemini API request failed: ${geminiResponse.status} ${await geminiResponse.text()}`);
  }

  const result = await geminiResponse.json();
  const review =
    result.candidates?.[0]?.content?.parts?.map((part) => part.text || '').join('\n') ||
    'Gemini review result was empty.';

  const body = `${MARKER}\n${review}\n\n---\nGenerated by the Gemini code review CI job.`;

  // 마커 기반 업데이트: 커밋마다 코멘트가 쌓이지 않게 기존 리뷰 코멘트를 갱신한다.
  let previous = null;
  for (let page = 1; page <= 10 && !previous; page += 1) {
    const notes = await gitlab(`${mrApi}/notes?per_page=100&page=${page}`);
    previous = notes.find((note) => note.body?.includes(MARKER)) || null;
    if (notes.length < 100) break;
  }

  if (previous) {
    await gitlab(`${mrApi}/notes/${previous.id}`, {
      method: 'PUT',
      body: JSON.stringify({ body }),
    });
    console.log('[ai-review] Updated existing review comment.');
  } else {
    await gitlab(`${mrApi}/notes`, {
      method: 'POST',
      body: JSON.stringify({ body }),
    });
    console.log('[ai-review] Posted new review comment.');
  }
} catch (error) {
  warnAndExit(`Unexpected failure: ${error.message}`);
}
