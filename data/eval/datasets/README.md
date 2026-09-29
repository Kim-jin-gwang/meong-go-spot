# 대표 테스트 데이터셋 (data/eval/datasets)

이미지 등록·유사도 매칭을 **반복 검증**하기 위한 표준 세트 (Jira #114).
임계값 비교(**-115**)와 성능 검증(**-58**)이 같은 데이터로 같은 잣대의 결과를 내는 것이 목적이다.

이미지 바이너리는 git에 없다 — 저장소에는 manifest·쌍 목록·통계만 커밋하고, 이미지는
서버 2 `/home/ubuntu/eval-app/datasets/repr-v1/images/`와 HDFS `/embeddings/eval/datasets/repr-v1/`에 둔다.

## repr-v1 구성

| 항목 | 값 |
|---|---|
| 사진 | 469장 (개체 328) — 개 239 / 고양이 170 / 기타 57 / 팀 샘플 3 |
| 양성 쌍 | 120 (개 60 / 고양이 45 / 기타 15 개체) — 코사인 밴드 hard 41 / mid 41 / easy 38 |
| 음성 쌍 | 440 — 채굴 혼동 240 (`neg_hard_mined`) + 같은 품종·색 100 (`neg_hard_attr`) + 다른 축종 100 (`neg_easy`) |
| 품종 | 43종 (1차 선별 품종당 캡 8개체) |
| 폴백(YOLO 미탐지) 사진 | 73장 — 저품질·개체 불명확 케이스, 품질 감지(-118)·폴백 경로 테스트용 |
| 시기 | 2024-01 ~ 2026-09 매 홀수 달 17개월 (TAR 풀 17개, 월당 첫 TAR) |
| 출처 | 공공데이터포털 유기동물 API 역사 수집분(HDFS `/data/shelter/images-backfill`) + 팀 자체 촬영 3장(개·다중 고양이·동물 없음, #56). **사용자 업로드 원본은 포함하지 않는다** |

### 파일

| 파일 | 내용 |
|---|---|
| `repr-v1/dataset.json` | 재현성 헤더 — 출처·수집 기간, 정답 기준, 전처리(마스킹·크롭 v2)·모델 버전(dinov2_vitb14 v2, 768차원, `ai/model.yaml`), 시드(20260908), TAR 풀 구성, 이미지 위치 |
| `repr-v1/manifest.jsonl` | 사진별 1행 — photo_id, 개체(group_id), 출처, HDFS TAR 경로, 축종·품종·색·성별, 폴백 여부, 역할(pos_query/pos_target/confuser/…), 시각 태그(각도·배경·품질) |
| `repr-v1/pairs.tsv` | 양성·음성 쌍 — `pair_type`, `label`(1/0), 양쪽 photo_id·개체·축종·품종, `cos_v2`(현행 v2 벡터 기준 코사인) |
| `repr-v1/stats.json` | 축별 분포 통계 (생성 시 자동 산출) |

### 정답 기준

- **양성** = 같은 `desertionNo`(개체)의 사진 1·2 — 공공 API가 개체당 2장을 제공하며, 두 벡터의
  코사인 ≥ 0.999(동일 파일)는 제외한다. `make_pairs.py` 규칙 그대로 ([상세](../README.md)).
  실종↔입소 실제 라벨은 존재하지 않으므로 이것이 유일한 자연 정답이다 — 실제 시나리오보다 쉬운
  편이라 절대값은 상한으로 해석한다.
- **음성** = 다른 개체의 사진 쌍. 세 단계 난이도:
  - `neg_hard_mined`: 풀 갤러리(32,322장)에서 질의와 코사인이 **가장 높은 다른 개체·같은 축종** —
    임계값이 실제로 걸러야 하는 최악의 상대
  - `neg_hard_attr`: 같은 품종·같은 색의 다른 개체
  - `neg_easy`: 다른 축종 무작위 — 곡선의 바닥 확인용

### 시각 태그 (촬영 각도 × 배경 × 품질)

양성 쌍 240장 + 팀 샘플 3장 = **243장을 전수 육안 태깅**했다 (`tags` 필드, 미태그 사진은 null).

| 축 | 값 (분포) |
|---|---|
| `angle` | `front` 149 · `side` 50 · `top` 33 · `back` 6 (no_animal 5장은 각도 없음) |
| `background` | `indoor`(실내·검사대) 103 · `cage`(케이지·철창) 88 · `plain`(단색·무배경) 35 · `outdoor`(실외) 17 |
| `quality` | `good` 142 · `occluded`(손·철창·수건에 가림) 61 · `dark`(저조도·야간) 14 · `blur`(흔들림·초점) 11 · `small`(저해상도·개체가 작음) 10 · `no_animal`(동물 없음) 5 |

태깅 중 확인한 데이터 특성 (사용 시 유의):

- **사진 2가 동물이 아닌 레코드가 실재한다** — 보호소가 질병 검사키트(CDV/FPV Ag) 사진을
  두 번째 사진으로 올리는 사례 4건 + 목걸이 접사 1건. `quality=no_animal`/`occluded` 태그로
  표시했으며, 해당 양성 쌍은 임계값 곡선에서 제외하거나 별도 분석한다. 품질 감지(-118)의
  실전 양성 케이스이기도 하다.
- **같은 배(litter) 형제 레코드가 동일 사진을 공유**하는 사례가 있다 (예: 새끼 고양이 상자
  사진). 음성 쌍에 cos ≥ 0.999인 쌍이 없음은 확인했지만, 형제끼리는 외형이 거의 같아
  `neg_hard_attr`에 정당한 최고 난이도 음성으로 포함될 수 있다.
- EXIF 회전이 적용되지 않아 90°/180° 돌아간 이미지가 소수 있다 — 전처리 파이프라인의
  방향 보정 검증 케이스로 활용 가능.

## 사용법

### -115 임계값 비교

`pairs.tsv`의 `label`과 `cos_v2`만으로 임계값 곡선(정밀도/재현율, ROC)을 바로 그릴 수 있다:

```python
import csv
rows = list(csv.DictReader(open("pairs.tsv", encoding="utf-8"), delimiter="\t"))
for th in [0.5, 0.6, 0.7, 0.8]:
    tp = sum(1 for r in rows if r["label"] == "1" and float(r["cos_v2"]) >= th)
    fp = sum(1 for r in rows if r["label"] == "0" and float(r["cos_v2"]) >= th)
    ...
```

다른 모델·전처리 버전과 비교할 때는 `images/`를 새 파이프라인으로 재임베딩해 같은 쌍의
코사인을 다시 계산한다 — 쌍 목록(photo_id)은 모델과 무관하므로 그대로 쓴다.
`neg_hard_mined`는 **v2 벡터 기준으로 채굴**된 것이라 다른 모델에서는 최악의 음성이 아닐 수
있다는 점만 유의 (모델 간 비교는 `neg_hard_attr`·`neg_easy`가 더 공정하다).

### -58 성능 검증

`images/`(정규화 전 원본, jpg/png 혼재, 469장)를 e2e 파이프라인(`ai/app/pipeline.py`) 입력으로
사용한다. 다양한 해상도·품질·폴백 케이스가 섞여 있어 대표 부하에 가깝다. 장수별 측정은
manifest에서 축종·품질 태그로 골라 구성한다.

### 정합성 검증 (갱신 후 필수)

```bash
# 서버 2 ~/eval-app 에서
~/ai/.venv/bin/python check_dataset.py --dataset datasets/repr-v1 --images datasets/repr-v1/images
```

manifest·pairs·이미지 파일의 상호 정합(파일 존재, label↔개체 일치)을 검사하고 축별 통계를
출력한다. 하나라도 어긋나면 종료 코드 1.

## 갱신 절차

1. 서버 2 `~/eval-app`에서 입력 준비 — 전체 정답쌍(`out/pairs-v2.tsv`), 풀 벡터(`vectors/`),
   레코드(`records/`). HDFS 원본 경로는 `dataset.json` 참조.
2. `make_dataset.py`로 재생성 — 시드·풀 구성을 바꾸지 않으면 같은 세트가 나온다(결정적).
   축을 늘릴 때는 `--pool-months`·쿼터 옵션으로 확장하고 `dataset_id`를 올린다(repr-v2, …).
3. `extract_images.py --list datasets/<id>/extract-list.tsv --out datasets/<id>/images`
4. 시각 태그 부여 후 `make_dataset.py --out-dir datasets/<id> --merge-tags tags.tsv`
5. `check_dataset.py` 통과 확인 → HDFS 업로드(`hdfs dfs -put datasets/<id> /embeddings/eval/datasets/`)
   → manifest·pairs·stats·README 변경분 커밋.

기존 `data/eval` 스크립트(`make_pairs.py`·`eval_recall.py`)의 인터페이스는 그대로다 —
recall 하네스와 이 데이터셋은 독립적으로 쓸 수 있다.
