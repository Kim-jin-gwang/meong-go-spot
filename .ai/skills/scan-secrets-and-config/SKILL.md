---
name: scan-secrets-and-config
description: Scan repository files, diffs, Git tracking state, logs, environment files, and configuration for secrets, sensitive data, unsafe defaults, and violations of this repository's configuration and gitignore rules. Use before committing or creating an MR.
---

# Scan Secrets and Configuration

먼저 다음 문서를 읽는다.

- `docs/code-convention.md`
- `docs/gitignore-guide.md`
- `docs/editor-config.md`
- `docs/git-attributes.md`
- `.gitignore`
- `.gitattributes`
- 관련 `.env.example` 또는 샘플 설정 파일 (루트 `.env.example`, `android/local.properties` 커밋 여부)

## Workflow

1. 현재 변경사항, staged 변경사항, Git 추적 파일을 분리해 검사한다.
2. 다음을 찾는다.
   - `.env`, `.env.*`, private key, certificate, local database
   - API key, access token, password, webhook URL, cloud secret
   - 로그에 포함된 개인정보와 인증정보
   - 운영 설정의 하드코딩
   - `.env.example` 없이 필요한 환경변수만 존재하는 경우
   - `.gitignore`에 있어도 이미 Git이 추적 중인 민감 파일
3. 발견한 값은 절대 출력하거나 복사하지 않는다. 파일 경로, 줄 번호, 값의 종류만 보고한다.
4. `.gitignore`에 추가하는 것만으로 이미 커밋된 Secret이 제거된다고 설명하지 않는다. history 노출 가능성을 별도로 경고한다.
5. 외부 API나 웹 서비스로 파일 내용을 전송하지 않는다.

## Output

```text
[BLOCKER] path:line
종류: access token
상태: 현재 diff에 포함됨
조치: 값을 폐기·교체하고 history 노출 여부를 확인
```

Secret 값 자체는 마스킹하더라도 필요 이상으로 재현하지 않는다. 기본적으로 파일을 수정하지 않는다.
