# 속성 태그 통제 어휘 (자카드 셀프조인 입력)

> 상태: **제안 — 팀 합의 대기** (#117).
> CLIP 사진 추론 태그가 보류되면서(팀 결정) 태그 원천을 **사용자 입력 + 공공 API 필드의 통제
> 어휘 정규화**로 바꾸는 제안이다. 어휘·매핑·스키마 자체는 실데이터 기반으로 완성돼 있고,
> 합의가 나면 [docs/data-ai-interface.md](../../docs/data-ai-interface.md) §3의 상태 표기만 확정으로 바꾼다.

## 파일

| 파일 | 내용 |
|---|---|
| [vocabulary.json](vocabulary.json) | 품종·색·크기·축종 canonical 어휘(코드+표시명)와 원시값→코드 정규화 매핑 규칙. `vocab_version: tags-v1` |
| [jaccard-input.schema.json](jaccard-input.schema.json) | MapReduce 자카드 셀프조인 입력 한 줄(JSONL)의 JSON 스키마 |

## 태그 원천 (제안)

| 속성 | 공공 건 (SHELTER) | 사용자 게시물 (USER_POST) |
|---|---|---|
| species | `upKindCd` — 코드 3종, 결측 0% | `species` 선택값 (필수) |
| breed | `kindCd`·`kindNm` — **코드 테이블 기반이라 표기 변형 0건 실측**. canonical 코드 = `k`+`kindCd` | `breed_name` 자유 입력 → label·별칭 정규화 매핑, `모름` 허용 |
| color | `colorCd` — 자유 텍스트(2024+ 고유값 6,098개) → 토큰 정규화 | `color` 자유 입력 → 같은 규칙, `모름` 허용 |
| size | `weight` 파싱 (소형 <8kg / 중형 8~20 / 대형 ≥20) | **입력 필드 없음 — 선택 필드 신설이 팀 합의 안건** |

## 핵심 규칙

- 태그는 `<속성>:<코드>` 문자열이고, 게시 건(공공 `desertionNo` / 사용자 `animal_case.id`) 단위 집합이다 —
  원천이 게시 건 단위 입력값이므로 사진ID가 아니라 **게시 건 ID가 집합의 키**다 (기존 §3 초안의 사진ID에서 변경).
- **`모름`·결측·매핑 불가 값은 태그를 생략한다(집합 미포함).** 공통 "미상" 토큰은 서로 무관한
  게시물끼리 자카드 유사도를 만들어내는 가짜 신호가 되기 때문이다. 매핑 실패 원시값은 생성기가
  로그로 남겨 어휘 개정 재료로 쓴다.
- 어휘 개정 = `vocab_version` 상향. 서로 다른 버전의 태그 집합을 한 조인에 섞지 않는다
  (임베딩 `model_version` 격리와 같은 원리).
- 개정 시 [data/collector/tools/check_vocab_coverage.py](../collector/tools/check_vocab_coverage.py)를 실데이터에 다시 돌려
  커버리지 회귀를 확인한다.

## 근거 데이터

실데이터 분포·커버리지 실측은 [data/experiments/2026-09-09-tag-vocabulary](../experiments/2026-09-09-tag-vocabulary/README.md).
