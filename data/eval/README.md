# 임베딩 평가 하네스 (data/eval)

모델 비교의 공정한 심판 — 정답쌍은 고정하고 벡터 디렉터리만 바꿔 같은 잣대로 recall@K 를 낸다
([docs/data-ai-interface.md](../../docs/data-ai-interface.md) §4 ⑤). 실종 사진↔입소 사진 라벨은 세상에 없으므로
**같은 개체의 사진 1·2**(공공 API 가 2024-06 부터 제공)를 자연 정답으로 쓴다.

## 구성

| 파일 | 역할 |
|---|---|
| `make_pairs.py` | 벡터 디렉터리에서 사진 1·2 가 모두 있는 개체를 (질의=사진 1, 정답=사진 2) 쌍으로 묶는다. 두 벡터의 코사인 ≥ 0.999 면 **동일 사진(duplicate)** 으로 표시. 레코드에서 축종·품종·보호소를 붙인다 |
| `eval_recall.py --snapshot DIR` | **이진 스냅샷으로 갤러리 로딩** (2026-09-10) — `index_tools.py snapshot` 산출물(`vectors-float32.npy`+`ids.txt`+`meta.json`)을 메모리맵으로 열어 TSV 파싱 65초를 0.1초로 줄인다. 형태·L2 정규화를 검사하고 어긋나면 실패한다(조용히 고치지 않는다). `--vectors-dir` 과 둘 중 하나만 준다 |
| `eval_recall.py` | 질의 벡터로 갤러리(모든 벡터, 자기 자신 제외) 를 검색해 정답의 순위를 구한다. recall@1/5/10/20, MRR, 중앙 순위. 폴백 패턴별·축종별, **갤러리를 같은 축종으로 제한**(제품 필터 재현), 같은 보호소 편향 진단, `--random-baseline` 자기 검증 |
| `eval_recall.py --assignments … --centers … --nprobe 1 2 4 8` | **K-Means 색인 후보 축소 손실** (2026-09-09): 질의와 가까운 중심 nprobe 개의 클러스터만 탐색했을 때의 recall@K·탐색 후보 비율·정답이 범위 밖인 비율을 정확 탐색(`overall`)과 같은 쌍으로 비교. 중심 배정은 K-Means 와 같은 유클리드 기준 |
| `eval_aggregation.py` | **동물 점수 집계 규칙 비교 + 임계값 곡선** (2026-09-14, 이슈 -143): 질의 = 정답쌍 사진 1 + 무관 사진 m 장, 후보를 **개체 단위**로 묶어 `max`·`top2_mean`·`mean_max`·폴백 제외(`fb_*`)·갤러리 폴백 가중(`*_gw`) 규칙별 recall@K 와, 정답 점수 분포 vs 최고 사칭자 점수 분포로 recall(τ)·false_alarm(τ)·표시 건수(τ) 곡선. 무관 사진의 개체는 갤러리에서 뺀다. 기록은 `data/experiments/2026-09-14-score-aggregation/` |
| `rank_eval.py` | **repr-v1 순위(랭킹) 검증** (2026-09-16, 이슈 -154): 표준 데이터셋의 양성 쿼리 120장으로 갤러리 469장을 검색해 recall@1/5/10·mRR 를 전체 + 난이도 밴드·축종·시각 태그(질의 쪽/정답 쪽)·폴백 패턴별로 분해. `quality=no_animal` 쌍은 본 지표에서 제외해 별도 표로, 폴백 73장은 갤러리 포함/제외 두 조건으로 비교. 결정적 실행(표본 추출 없음). 임계값 곡선(-115, 점수 축)과 겹치지 않는 순위 축 — 결과는 [results/repr-v1-rank-v2.md](results/repr-v1-rank-v2.md) |
| `post_rank_eval.py` | **게시물(개체) 단위 집계 규칙 순위 비교** (2026-09-17, 이슈 -155): 질의 = 양성 쌍 개체의 사진 2장 묶음, 후보 = 개체 328 로 `max`·`mean`·`rep1`(대표 1장)·`mean_max` 의 recall@1/5/10·mRR 를 전체 + 난이도 밴드·축종·질의 품질 태그별로 비교. no_animal 사진 포함/제외 두 조건. 개체당 사진 2장 제약으로 **정답 점수는 규칙 불변** — 사칭자 측 집계 차이만 잰다 (해석 주의는 [results/repr-v1-post-agg.md](results/repr-v1-post-agg.md)). -143(`eval_aggregation.py`, 혼입 강건성 축)과 보완 관계 |
| `results/` | 표준 데이터셋 기반 검증 결과 — 요약 md + 기계용 json·쌍별 순위 tsv (서버 2 실행 산출물 커밋) |
| `cluster_purity.py` | K-Means 배정의 축종(`upKindCd`)·품종(`kindCd`) purity 와 클러스터 크기 분포. 같은 크기 분포에 라벨을 무작위로 섞은 purity 를 기준선으로 함께 낸다 |
| `make_dataset.py` | **대표 테스트 데이터셋 생성** — 월별 TAR 풀에서 층화 선별(축종 × 폴백 × 난이도, 품종 캡) + 음성쌍 채굴(최고 코사인 혼동·같은 품종·색·다른 축종). 시각 태그 병합(`--merge-tags`) 포함. 산출물은 [datasets/](datasets/README.md) |
| `extract_images.py` | 데이터셋 이미지를 HDFS TAR 스트리밍에서 추출 (서버 2) |
| `check_dataset.py` | 데이터셋 manifest·pairs·이미지 정합성 검증 + 축별 통계 — 갱신 후 필수 통과 |
| `datasets/` | 표준 데이터셋 manifest·쌍 목록 (이미지는 서버 2·HDFS) — 임계값 비교(-115)·성능 검증(-58)용 |
| `tests/` | 3차원 합성 벡터로 쌍 생성·중복 표시·순위 정확성·무작위 기준선·데이터셋 생성 검증 (CI `data-check`) |

## 실행 (서버 2, `~/ai/.venv` 에 numpy)

```bash
# 입력: HDFS 에서 내려온 벡터(vectors-*.tsv + detections-*.jsonl, 월 디렉터리 포함)와 백필 레코드
python make_pairs.py --vectors-dir vectors --records "records/*.jsonl" --out out/pairs.tsv
python eval_recall.py --vectors-dir vectors --pairs out/pairs.tsv --sample 2000 --random-baseline --out out/recall-random.json
python eval_recall.py --vectors-dir vectors --pairs out/pairs.tsv --records "records/*.jsonl" --sample 20000 --out out/recall-v2.json
# K-Means 색인 손실 (배정·중심은 HDFS index/kmeans-k256/ 에서 내려온 것) — 같은 --seed·--sample 이라 overall 과 직접 비교된다
python eval_recall.py --vectors-dir vectors --pairs out/pairs.tsv --sample 20000 --assignments index/assignments --centers index/centers.tsv --nprobe 1 2 4 8 16 --out out/recall-k256.json
python cluster_purity.py --assignments index/assignments --records "records/*.jsonl" --out out/purity-k256.json
```

### repr-v1 순위 검증 재현 (이슈 -154)

```bash
# 서버 2 ~/eval-app — 풀 벡터에서 repr-v1 의 469장만 추출해 쓴다 (재임베딩 불필요, 4초)
~/ai/.venv/bin/python rank_eval.py --dataset datasets/repr-v1 \
  --vectors-dir vectors vectors-samples --out out/repr-v1-rank-v2.json
```

- 팀 샘플 3장(cats·dog·none)은 백필 벡터에 없다 — `vectors-samples/vectors-samples.tsv` 로 보충한다.
  없으면 `~/ai/.venv/bin/python -m app.pipeline datasets/repr-v1/images/{cats,dog,none}.jpg --out out/samples-embed.json`
  으로 v2 파이프라인 임베딩 후 `photo_id\tv1,...` TSV 로 변환해 둔다 (갤러리 전용, 질의·정답 아님)
- 결정적 실행 — 표본 추출·무작위성이 없어 같은 입력이면 출력이 바이트까지 같다 (시드 불필요)
- 결과 요약·해석은 [results/repr-v1-rank-v2.md](results/repr-v1-rank-v2.md), 산출 json·tsv 도 같은 디렉터리에 커밋한다

### repr-v1 게시물 단위 집계 비교 재현 (이슈 -155)

```bash
# 서버 2 ~/eval-app — 벡터 준비는 -154 와 동일 (vectors + vectors-samples)
~/ai/.venv/bin/python post_rank_eval.py --dataset datasets/repr-v1 \
  --vectors-dir vectors vectors-samples --out out/repr-v1-post-agg.json
```

- 결정적 실행 — 결과 요약·권고안은 [results/repr-v1-post-agg.md](results/repr-v1-post-agg.md)
- 임계값 τ=0.60 과 Top-K 순위의 교차 분석(-156)은 [results/repr-v1-threshold-rank-cross.md](results/repr-v1-threshold-rank-cross.md)
  — 새 실행 없이 post-agg.tsv × pairs.tsv `cos_v2` 조인 파생

- 갤러리 45만 × 768 float32 ≈ 1.4GB 메모리. 질의 500개 묶음으로 내적하므로 (질의 × 갤러리) 행렬을 한 번에 들지 않는다
- 갤러리는 `--vectors-dir`(TSV, 65초) 또는 `--snapshot`(이진, 0.1초) 중 하나로 읽는다. 스냅샷은 매일 22:30 파이프라인이 갱신하므로 **일일 증분까지 포함된 최신 갤러리**다 — HDFS `/embeddings/{model}/{ver}/snapshot/` 를 내려 쓰면 로컬 TSV 사본을 따로 유지할 필요가 없다
- `--sample 0` 이면 전체 쌍 평가 (19만 쌍 ≈ 20~30분). 모델 비교는 같은 `--seed`·`--sample` 로 같은 부분집합을 쓴다
- 무작위 기준선의 recall@K 는 K/N(≈ 20/452,000 = 0.00004) 근처여야 채점기가 정상이다

## 지표 정의

- rank(t) = 1 + |{ g ∈ 갤러리∖{q} : cos(q,g) > cos(q,t) }| — 동점은 정답에 유리하게 센다
- recall@K = rank ≤ K 인 쌍의 비율, MRR = mean(1/rank)
- `by_fallback`: both_detected / one_fallback / both_fallback — 폴백(YOLO 미탐지·원본 전체 임베딩) 이 매칭 품질에 미치는 영향
- `same_up_kind_gallery`: 갤러리를 질의와 같은 축종(`upKindCd`) 으로 제한 — 제품이 실제로 하는 필터
- `shelter_bias`: 같은 보호소 다른 개체 vs 무작위 다른 개체의 평균 코사인 — 워터마크·배경 편향 진단

## 알려진 한계

- 정답쌍은 "같은 개체의 다른 사진"이라 실제 시나리오(사용자 촬영 vs 보호소 촬영, 시간차) 보다 쉬운 편이다. 절대값보다 **모델·설정 간 상대 비교**에 쓴다
- 사진 1·2 가 다른 시점에 교체되는 레코드가 있어 일부 쌍은 실제로 다른 개체일 수 있다 (API 특성, 비율 미측정)
