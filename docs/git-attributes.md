# .gitattributes 가이드

> 설정 원본: 루트 `.gitattributes` — 이 문서는 근거 설명이며, 규칙 자체는 설정 파일이 정의합니다.

## 무엇을 하는가

**Git이 commit·checkout·diff할 때**의 처리를 통일합니다. `.editorconfig`가 에디터 저장 시점을 담당한다면, 이 파일은 Git 저장 시점을 담당합니다.

| 파일 | 역할 |
|---|---|
| `.editorconfig` | **에디터**가 저장할 때의 포맷 |
| `.gitattributes` | **Git**이 commit·checkout·diff할 때의 처리 |

## 현재 설정의 근거

- `* text=auto eol=lf` — 모든 텍스트 파일을 저장소에 LF로 정규화. Windows 팀원의 CRLF가 저장소에 섞이는 것을 차단
- `*.bat`, `*.cmd`는 CRLF — Windows 스크립트 요구사항
- 이미지·아카이브·오피스 파일은 `binary` — 텍스트 diff·개행 변환 대상에서 제외

## 주의

이미 파일이 들어간 뒤 이 파일을 수정했다면 정규화 커밋 1회가 필요합니다.

```bash
git add --renormalize .
git commit -m "chore: normalize line endings"
```
