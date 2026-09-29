# .gitignore 가이드

> 설정 원본: 루트 `.gitignore` (단일 파일 — android/backend 하위에 별도 .gitignore를 만들지 않습니다)

## 반드시 제외

| 패턴 | 이유 |
|---|---|
| `.env`, `.env.*` | 실제 secret·로컬 환경값 |
| `*.pem`, `*.key`, `*.p12`, `*.pfx` | 인증서·private key |
| local DB 파일 | 로컬 데이터·개인정보 |
| `node_modules/`, `.gradle/` | 재설치 가능 |
| `dist/`, `build/` | 재생성 가능 |

## 반드시 추적

- `.env.example` (`!.env.example` 예외 규칙 — 실제 키 목록을 채워서 커밋)
- lockfile 전체 (`package-lock.json`)
- `build.gradle`, `gradle-wrapper.jar` (`!**/gradle/wrapper/gradle-wrapper.jar` 예외)
- `.editorconfig` · `.gitattributes`
- `**/.vscode/extensions.json`, `**/.vscode/settings.json` (팀 공유 에디터 설정)

## 예외 규칙 패턴

```gitignore
.env
.env.*
!.env.example

**/.vscode/*
!**/.vscode/extensions.json
!**/.vscode/settings.json
```

> 패턴에 `/`가 중간에 들어가면 루트에 앵커됩니다. 하위 디렉터리까지 적용하려면 `**/` prefix가 필요합니다 (backend/gradle wrapper 예외가 이 사례).

## 검증

```bash
git status --ignored
```

## ⚠️ 이미 커밋된 Secret

**`.gitignore` 추가만으로 해결되지 않습니다.** 즉시 값을 폐기·교체하고 history 노출 여부를 점검하세요. `scan-secrets-and-config` 스킬이 이 상태를 검사합니다.
