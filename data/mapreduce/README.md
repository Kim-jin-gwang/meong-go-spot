# MapReduce 알고리즘 모듈 (data/mapreduce)

매칭 본체인 **MapReduce 5종(D3) 전체 구현**. 클러스터 JVM과 맞춘 **Java 17** (backend의 21과 다름 — 의도된 차이).

| # | 알고리즘 | 역할 | 진입점 |
|---|---|---|---|
| 1 | K-Means (+k-means++) | 벡터 공간 클러스터 색인 — 블로킹 기반 | `kmeans.InitCenters` → `kmeans.KMeansDriver` |
| 2 | kNN 조인 | 클러스터 안에서 신고×동물 Top-K (match_candidate 원형) | `knn.KnnJoinDriver` |
| 3 | 행렬곱 | 정규화 벡터 블록 행렬곱 = 코사인 유사도 전량 정밀 계산 | `matmul.MatMulDriver` |
| 4 | 쎄타조인 | 품종·지역 등가 블로킹 + 날짜 비등가(θ) 술어 | `theta.ThetaJoinDriver` |
| 5 | 자카드 셀프조인 | 속성 태그 유사 쌍 (후보 생성 → 점수 집계 2단계) | `jaccard.JaccardDriver` |

## 빌드·배포

```bash
cd data/mapreduce && ./gradlew test jar   # build/libs/mungo-mapreduce.jar (thin — 하둡이 런타임 제공)
scp build/libs/mungo-mapreduce.jar ubuntu@<server2-host>:~/kmeans-test/
```

## 실행 (서버 2 — 인자 상세는 각 Driver의 Javadoc)

벡터 계약: `id<TAB>v1,v2,...` / 메타 계약: `id<TAB>품종<TAB>지역<TAB>YYYYMMDD` / 태그 계약: `id<TAB>tag1,tag2,...`

```bash
J=mungo-mapreduce.jar
hadoop jar $J com.mungo.mapreduce.kmeans.InitCenters   <vectors> 20 <centers> 42
hadoop jar $J com.mungo.mapreduce.kmeans.KMeansDriver  <vectors> <centers> <work> 20 1e-4
hadoop jar $J com.mungo.mapreduce.knn.KnnJoinDriver    <queries> <vectors> <수렴중심> <out> 20
hadoop jar $J com.mungo.mapreduce.matmul.MatMulDriver  <queries> <vectors> <out> 0.8 2 8
hadoop jar $J com.mungo.mapreduce.theta.ThetaJoinDriver <query-meta> <shelter-meta> <out> 90
hadoop jar $J com.mungo.mapreduce.jaccard.JaccardDriver <tags> <work> 0.6
```

입력 준비·채점 도구는 `tools/`: 합성 벡터(`make_synthetic_vectors`), 질의(`make_queries`),
메타(`make_theta_inputs`), 태그(`make_jaccard_input`) + 각 `eval_*` 채점기.

### 벡터 차원 — 상수 없음, 메타데이터에서 읽고 검증 (계약 §4 ③, 2026-09-09)

벡터를 다루는 잡(K-Means·kNN·행렬곱)은 차원을 **어디에도 상수로 두지 않는다**. 드라이버에
`-Dmungo.vector.meta=/embeddings/{model}/{ver}/_meta.json`(또는 `-Dmungo.vector.dim=768`)을 주면
`common.Dimensions`가 `dim`을 읽어 conf에 심고, 매퍼는 setup에서 **중심 파일 차원 = 설정 차원**을 확인한 뒤
**모든 입력 벡터를 그 차원으로 검증**한다. 설정이 없으면 중심(또는 첫 벡터)의 차원으로 잠근다 — 어느 경우든
한 잡 안에 다른 차원이 섞이면 첫 줄에서 예외로 죽는다. 예전 `squaredDistance`는 짧은 쪽 길이만 돌아
384차원 벡터와 768차원 중심의 거리를 "그럴듯한 값"으로 냈는데, 이제 길이가 다르면 예외다 (계약 원칙 2:
다른 모델의 벡터는 섞이지 않는다 — 조용한 쓰레기 값이 가장 위험).

### 실벡터 K-Means 색인 (2026-09-09)

```bash
# 서버 2 ~/kmeans-real — 경로는 /embeddings/ACTIVE 에서 만든다 (모델 교체 시 스크립트 변경 없음)
nohup ./run_real_kmeans.sh 256 10 1e-3 100000 42 > logs/nohup-k256.out 2>&1 &
```

- 입력 글롭 `…/shelter-backfill/yyyymm=*/vectors-*.tsv`(261파일, 3.1GB, 452,486×768). `KMeansDriver`는
  `CombineTextInputFormat`(기본 128MB, `-Dkmeans.split.maxsize`)으로 묶어 **맵 24개**로 돈다 — 파일당 맵 1개면
  기동 오버헤드(각 ~3초×261)가 계산을 넘는다.
- `InitCenters`는 `-Dkmeans.init.sample=N` 저수지 표본으로 k-means++를 돈다 — 45만×768 double은 2.8GB라
  전량은 클라이언트 힙을 넘긴다. 표본 10만: 읽기 49초 + k-means++ 39초.
- 산출: `/embeddings/{model}/{ver}/index/kmeans-k{K}/` 아래 `centers-init.tsv`, `work/centers-N/`,
  `work/assignments/`(`id\tcluster`), `centers.tsv`(최종 중심 고정 이름 — kNN 조인·상주 서비스는 이것만 본다).
- 실측·손실 곡선·purity는 `data/experiments/2026-09-09-kmeans-real/`.

## 검증 이력 (YARN 2노드 실측, 2026-09-02)

모든 알고리즘을 "정답을 아는 입력" 또는 "로컬 독립 재계산"과 대조해 채점했다.

| 알고리즘 | 입력 | 결과 |
|---|---|---|
| K-Means | 합성 13,000×64, 정답 20군집 | 무작위 init purity 0.80(군집 병합) → **k-means++ 1.00**, 회귀(다른 시드) 0.95 |
| kNN 조인 | 노이즈 질의 200 (정답=원본) | **Top-1 적중 1.00 · Top-20 포함 1.00**, 잡 24초 |
| 행렬곱 | 질의 200 × 13,000 | 13만 쌍 출력, 표본 13,076쌍 로컬 재계산 대조 **불일치 0** (1e-6), 23초 |
| 쎄타조인 | **실수집 6,807건** × 합성 신고 300 | 52,433쌍 — 로컬 전량 재계산과 **누락 0·초과 0**, 20초 |
| 자카드 | **실수집 태그 6,807건** | 191만 쌍(≥0.6), 표본 100 id 전량 대조 **불일치 0**, 92초 (2잡) |
| K-Means **실벡터** (2026-09-09) | **452,486×768 실임베딩**, k=256 | 초기화 91초 + 10반복 1,426초(반복당 ~130초, 맵 24, CPU 바운드) + 배정 = **25분**. 후보를 최근접 16클러스터(7%)로 줄여도 **recall@20 0.649→0.648**(손실 0.001), 축종 purity 0.99·품종 0.86 — 상세 `data/experiments/2026-09-09-kmeans-real/` |

**운영 시사점**: 잡 하나의 기동 오버헤드가 ~15초+이므로 M2 버튼 요청마다 새 YARN 잡을
실행하지 않는다. M2는 PostgreSQL `match_run(PENDING)`만 만들고, 장기 실행 DATA/AI worker가
준비된 임베딩·색인과 이 모듈이 소유한 Top-K 규칙을 사용해 결과를 멱등 적재한다. 이 MapReduce
체인은 검색 대상 사전 계산·대규모 갱신·묶음 처리에 사용한다.
사전 계산 결과 조회 또는 SLA 재조정이 필요하다 (아키텍처 NFR 토의 안건).

## 알려진 한계 (부채)

- `InitCenters`는 벡터를 메모리에 올린다 — 45만×768부터 전량은 힙을 넘겨 `-Dkmeans.init.sample`(저수지 표본)로 돈다(2026-09-09). 수백만 규모는 k-means||로 교체. `KnnReducer`는 방 전체를 올린다 — 방이 수십만이면 방 분할
- 자카드 후보쌍 수는 태그 빈도의 제곱 — 저변별 태그 제거(입력 도구)가 비용 통제 수단이며,
  규모 확대 시 prefix filtering 도입
- `npm run check` 미포함 모듈 — MR 게이트 연결은 후속
- Combiner는 합·건수만 전달 (평균의 평균은 오답) — 수정 시 이 불변식을 지킬 것
