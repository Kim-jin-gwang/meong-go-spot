# .editorconfig 가이드

> 설정 원본: 루트 `.editorconfig` — 이 문서는 근거 설명이며, 규칙 자체는 설정 파일이 정의합니다.

## 무엇을 하는가

**에디터가 저장할 때**의 포맷(인코딩·개행·들여쓰기)을 팀 전체에서 통일합니다. VS Code·IntelliJ 모두 기본 지원.

## 현재 설정의 근거

| 규칙 | 값 | 이유 |
|---|---|---|
| charset | `utf-8` | 한글 주석·문서 안전 |
| end_of_line | `lf` | 저장소 표준 개행 (`.gitattributes`와 일치) |
| indent | space 2 | JS/TS/Vue 생태계 표준 |
| Java·Kotlin·Python | space 4 | 언어 관례 (Spotless AOSP 스타일과 일치) |
| Markdown | trailing whitespace 유지 | 줄 끝 공백 2개가 줄바꿈 문법 |
| `.bat`/`.cmd` | `crlf` | Windows 스크립트는 CRLF 필수 |

## 주의

`.editorconfig`는 포매터를 **대체하지 않습니다.** Prettier·Spotless가 있는 영역은 그쪽이 우선하며, 이 파일은 포매터가 없는 파일(문서·설정 등)의 하한선입니다.
