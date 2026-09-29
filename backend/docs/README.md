# Backend 문서

Spring Boot 백엔드를 구현할 때 사용하는 개발 가이드입니다. 제품 범위와 파트 간 계약은
루트 `docs/`의 기준 문서를 우선합니다.

## 시작 순서

1. [Backend Codex 개발 시작 가이드](backend-codex-start-guide.md) — Jira 이슈 확인부터 구현·검증·MR 준비까지
2. [Backend API 개발 가이드](backend-api-development-guide.md) — 계층 책임, 패키지 구조와 테스트 우선 구현 절차
3. [Backend 공통 설정 가이드](backend-common-settings.md) — 공통 응답, 오류 처리, Security와 JPA Auditing
4. [Backend Ping API 가이드](backend-ping-guide.md) — 실행 가능한 성공·오류 응답 예제
5. [회원가입 개발 안내](member-signup-guide.md) — 전화 인증·가입 API, 로컬 Secret 생성·Redis 실행과 검증
6. [로그인·인증 세션 개발 안내](auth-session-guide.md) — JWT 키 준비, 로그인·갱신·로그아웃과 실패 제한
7. [게시물 공통 도메인·사진 처리 안내](post-photo-guide.md) — 사진 검증·HDFS 반영, 조회 권한과 저장소 운영 준비
8. [게시물 등록 안내](post-creation-guide.md) — LOST·SHELTERING 등록, 멱등성·위치 기준 데이터·암호화 설정
9. [게시물 조회 안내](post-query-guide.md) — 공개·내 목록 커서, 출처별 상세와 위치 공개·보존 범위
10. [게시물 관리 안내](post-management-guide.md) — 부분 수정, 사진 전체 교체와 종료·버전 검증
11. [매칭 요청·후보 조회 안내](matching-api-guide.md) — 비동기 접수, 이전 결과·STALE과 후보 공개 범위
12. [1:1 텍스트 채팅 안내](chat-api-guide.md) — 참여자 권한, 방·메시지 커서와 중복 전송 방지
13. [보호동물 데이터 상태 API](ingestion-status-api-guide.md) — 갱신 상태 판정과 최신 일일 입소 요약
14. [외부 연동 준비 가이드](external-integration-readiness-guide.md) — FCM 일일 요약·DATA/AI worker 구현 전 계정·Secret·운영 계약

## 함께 보는 공통 계약

- [AGENTS.md](../../AGENTS.md) — 저장소 전체 작업 규칙과 문서 우선순위
- [API 명세](../../docs/api-spec.md) — Android와 Backend 사이의 요청·응답 계약
- [ERD](../../docs/erd.md) — PostgreSQL 스키마와 데이터 수명주기
- [코드 컨벤션](../../docs/code-convention.md) — 프로젝트 전체 코드 작성 규칙
- [백엔드 보안·운영 정책](../../docs/backend-security-operations-policy.md) — 인증, 개인정보, migration과 운영 안전장치

문서와 실제 프로젝트 설정이 다르면 임의로 한쪽을 선택하지 말고 정책 충돌로 보고합니다.

## 인계·감사 기록

- [백엔드 Jira 완료 조건 감사](backend-jira-completion-audit.md) — Jira `59`·`60`·`63`·`65`의 완료 조건별 코드·DB·테스트 증거와 후속 구현 순서
