# 스케일 실험 — K-Means 분산 실측 (2026-09-03)

**질문**: 우리 클러스터에서 분산 처리가 실제로 기여하는가? Combiner는 얼마나 중요한가?
**데이터**: 합성 벡터 2,000,000 × 128차원 (2.45GB = **HDFS 블록 19개**), k=50, 시드 42.
**잡 구성**: K-Means 2반복 + 배정 잡 (수렴 무관, 시간 측정 목적 — 초기 중심은 데이터 무작위 표본).
**환경**: Hadoop 3.5.0 2노드 (bd-master NM 6GB / bd-worker1 NM 4GB), 코드 `feat/scale-experiment`.
원시 카운터는 [metrics.json](metrics.json), 실행 도구는 `data/mapreduce/tools/run_scale_phase.sh`.

## 결과 1 — 스케일아웃: 노드를 늘리면 실제로 빨라진다

| 구성 | 벽시계 | 동시 컨테이너 (최대) | 스피드업 | 효율 |
|---|---|---|---|---|
| 1노드 (Combiner on) | 390초 | bd-master 5 | 1.00× | — |
| **2노드 (Combiner on)** | **247초** | bd-master 6 + **bd-worker1 4** | **1.58×** | 79% |

- 맵 태스크 19개가 두 노드에 실제로 분산 배치됨 (컨테이너 폴링 실측)
- 효율이 100%가 아닌 이유 = **암달의 법칙 실측**: 잡 기동 고정비(~15-20초×3잡)와 리듀스 직렬 구간은 노드를 늘려도 줄지 않는다
- **데이터 지역성 100%** (Data-local maps 19/19, 전 페이즈) — "계산을 데이터가 있는 곳으로 보낸다"는 MapReduce 원칙이 완벽히 동작

## 결과 2 — Combiner: 최적화가 아니라 생존 조건

| 구성 | 셔플 크기 | 결과 |
|---|---|---|
| **Combiner on** | **4.3MB** (레코드 200만 → **1,850개**로 접힘, 1/1081) | 247초 정상 |
| Combiner off (기본 1GB 리듀서) | **2,455MB (571배)** | 💥 **FAILED** — `OutOfMemoryError` (셔플 페처) |
| Combiner off (리듀서 3GB 증설) | 2,455MB | 295초 (+19%) — 메모리 3배를 내고 겨우 통과 |

- Combiner("합·건수만 전달, 평균의 평균 금지")가 맵 쪽에서 200만 레코드를 클러스터 수(≈k×맵수)로 접어 셔플을 **571배** 줄인다
- 이게 없으면 기본 설정에서 잡이 느려지는 게 아니라 **죽는다** — 코드 리뷰에서 "Combiner 불변식을 지켜라"라고 적어둔 이유의 정량 증명

## 해석 요약 (발표용 세 문장)

1. 블록 19개짜리 입력에서 맵 태스크 19개가 두 노드에 지역성 100%로 분산 배치됐고, 노드 2배는 실측 1.58배 속도 향상을 줬다 (효율 79% — 고정비·직렬 구간이 암달 한계).
2. Combiner는 셔플을 571배(2.4GB→4.3MB) 압축했으며, 껐더니 기본 메모리에서 잡이 OOM으로 사망했다.
3. 따라서 이 파이프라인의 확장성은 "노드 추가"와 "셔플 설계(키·Combiner)" 두 축이 함께 결정한다.

## 재현

```bash
# 서버 2에서 (자세한 준비는 data/mapreduce/README.md)
python3 make_synthetic_vectors.py --n 2000000 --dim 128 --clusters 50 --out ~/scale-data
hdfs dfs -put ~/scale-data/{vectors,centers}.tsv /scale/
./run_scale_phase.sh 2nodes true          # Phase A
# (서버1 NM 정지 + RM 재시작 → 1노드) ./run_scale_phase.sh 1node true   # Phase B
./run_scale_phase.sh nocombiner false     # Phase C — 기본 설정에선 OOM으로 실패한다 (의도된 재현)
```
