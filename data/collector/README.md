# 수집기 (data/collector)

공공데이터포털 구조동물 API를 수집해 Kafka로 흘리고 HDFS에 적재(메타데이터·이미지)하는 모듈.
일일 자동 실행은 Airflow DAG(`data/airflow/dags/collector_daily.py`, 매일 22:30 KST)이 담당한다
— 운영 방법은 [docs/bigdata-cluster.md](../../docs/bigdata-cluster.md)의 "일일 수집 파이프라인" 절.
전체 설계는 [docs/data-ai-interface.md](../../docs/data-ai-interface.md)와 [docs/bigdata-cluster.md](../../docs/bigdata-cluster.md) 참조.

## 요구 사항

- Python 3.11+
- 환경변수 `DATA_GO_KR_SERVICE_KEY` (루트 `.env.example` 참조)
- Kafka 발행·적재를 쓸 때만: `pip install -r requirements.txt` (confluent-kafka)
- 테스트: `pip install -r requirements.txt -r requirements-dev.txt` 후 루트에서 `npm run check:data`
  (= `python -m pytest data --ignore=data/mapreduce -q`, 임베딩 러너 테스트 포함). 네트워크·Kafka·HDFS 없이 돌며 CI `data-check` 잡과 동일.
  서버 환경이 필요한 검증은 `data/experiments/` 실행 기록이 담당한다

## 실행

```bash
cd data/collector
# 수집만 (의존성 불필요)
DATA_GO_KR_SERVICE_KEY=<키> python main.py --out ./out --state ./out/state.json

# 수집 + 증분을 Kafka shelter.raw 토픽에 발행
DATA_GO_KR_SERVICE_KEY=<키> python main.py --out ./out --state ./out/state.json --kafka localhost:9092

# 토픽 → HDFS 적재 (서버에서 — hdfs CLI 필요)
python loader.py --bootstrap bd-master:9092 --hdfs-dir /data/shelter/raw

# 토픽 → 서비스 PostgreSQL 정규화 적재 (승인된 지역 CSV·SHELTER_POSTGRES_DSN 필요)
python postgres_loader.py --bootstrap bd-master:9092 --requested-from 2026-09-10 --requested-to 2026-09-10

# 토픽 → 사진 다운로드 → HDFS tar 적재 (서버에서)
python imager.py --bootstrap bd-master:9092 --hdfs-dir /data/shelter/images

# 분실동물 API 전량 → HDFS 일일 스냅샷 (서버 2 DAG, 연락처·상세주소 제거 — 최근 1개월치만 주는 API 라 매일 남긴다)
DATA_GO_KR_SERVICE_KEY=<키> python lost_snapshot.py --hdfs-dir /data/lost/raw --breed-species reference/breed-species.csv

# 그날 분실 스냅샷 → 서비스 PostgreSQL PUBLIC/LOST 적재 (서버 1 타이머 23:20, V3 마이그레이션 뒤)
python lost_ingestion.py --date 2026-09-15

# HDFS 백필 최근 3년 → 보호소 결과 통계(반환·입양 비율, 공고 기간) → dashboard_stat (서버 1 타이머 월 00:40, V4 뒤)
python shelter_outcomes.py --print-only      # DB 없이 payload 만 확인
```

로컬 개발용 Kafka: 루트에서 `docker compose -f compose.dev.yml up -d kafka` (localhost:9092).

- `out/dt=YYYY-MM-DD/records.jsonl` — 당일 전량 스냅샷 (API 원문 그대로, 가공 없음)
- `out/dt=YYYY-MM-DD/delta.jsonl` — 직전 실행 대비 신규+변경분 (Kafka로 흘릴 대상)
- `state.json` — `desertionNo → updTm` 맵. 첫 실행은 전량이 신규로 잡히고, 이후 변경분만 delta에 남는다
- **창 밖 재조회** — API 는 `bgnde/endde` 없이 부르면 접수일 최근 31일만 주므로(`api.DEFAULT_WINDOW_DAYS`, 2026-09-24 실측)
  접수 31일이 지난 동물은 보호중 → 종료로 바뀌어도 일일 수집에 안 잡혔다(2026-09-25 운영 DB: 8월 접수 보호중 863건이 한 달째
  그대로). 그래서 매일 기본 창 뒤에 접수일 `--resync-days`(기본 180) 전까지를 백필과 같은 월 슬라이스로 한 번 더 받아
  **상태 파일에 있는 desertionNo 만** 같은 `updTm` 증분 판정에 태운다 — 일일 수집 전에 접수된 동물(드라이런 39,387건 중
  34,167건)까지 신규로 흘리면 사진 수만 장이 이미지·임베딩 태스크로 쏟아져 DAG 시한을 넘기므로 과거 전량은 백필의 몫으로 둔다.
  비용은 월당 8쪽 안팎 × 5개월 ≈ 40호출·1분 남짓. 재조회 원문 파일은 남기지 않고 변경분만 `delta.jsonl` 에 들어간다.
  슬라이스 하나가 실패하거나 건수가 허용치(`api.count_tolerance`, 백필과 같은 max(20, 0.5%))보다 어긋나면 **그 달만**
  경고와 함께 건너뛰고 나머지와 당일 증분은 그대로 나간다 (다음 날 다시 시도). 과거 월은 totalCount 와 항목 수가 몇 건씩
  어긋나는 게 정상이라(2026-09-25 실측 2026-04: 7318 대 7317) 소량 차이는 그대로 쓴다. `--resync-days 0` 이면 끈다
- 수집 건수가 API `totalCount`와 다르면 종료 코드 1로 실패한다 (부분 수집을 성공으로 오인 방지)

## 역사 백필 (backfill.py)

과거 공고 전량을 월 단위로 수집해 HDFS에 직행 적재한다 — Kafka는 일일 증분의 통로(보존 7일)라 벌크는 우회.

```bash
# 서버 2에서 — 재개 가능: 중단되면 같은 명령으로 다시 실행하면 완료된 월은 건너뛴다
python backfill.py --from 2008-01 --to 2026-09 --hdfs-dir /data/shelter/backfill \
    --state ~/collector-data/backfill-state.json --metrics ~/collector-data/backfill-metrics.json
```

- 정찰 실측(2026-09-03): 2008~2026 **1,634,915건 = 1,643호출** (일 쿼터 10,000의 16%). 이미지는 **2022-01 이후만 파일이 생존** (2021 이전은 URL만 남은 유령 — 정찰에서 연도별 GET 표본으로 확인)
- 안전장치: 월별 totalCount와 수집 수의 차이가 **max(20건, 0.5%)를 넘으면** 그 월은 미완료로 남김
  (과거 월은 API 집계가 1~11건씩 어긋나므로 소량은 허용하고 `diff`를 지표에 기록 — 일일 수집기의
  정확 일치 원칙과 다른 이유), 일 호출 9,000 도달 시 자동 중단(다음날 재개), 초당 5호출 상한
- 지표(`--metrics`): 월별 대조표, 호출·재시도, 연도별 결측률(이미지 URL·updTm·품종·지역), 연도별 상태 분포, **반환 케이스 발견→종료 일수 히스토그램**(골든타임 분석 재료)
- 적재 경로: `<hdfs-dir>/yyyymm=YYYYMM/records.jsonl` (월당 1파일)
- 실행 기록·해석: [data/experiments/2026-09-03-backfill](../experiments/2026-09-03-backfill/README.md)
  (225개월 1,634,699건, 반환 골든타임 3일 69%, 이미지 파일 생존 경계 2022-01)

## 이미지 역사 백필 (image_backfill.py)

백필 레코드(HDFS)의 `popfile1·2`를 내려받아 tar로 적재한다. imager와 다운로드·포맷 판별·spool 로직을
공유하되 입력은 Kafka가 아니라 HDFS 레코드다 — 벌크는 Kafka를 우회한다.

```bash
# 서버 2에서 — 청크(2,000장) 단위 재개: 중단되면 같은 명령으로 재실행
python -u image_backfill.py --from 2024-01 --to 2026-09 --workers 16 \
    --records-dir /data/shelter/backfill --hdfs-dir /data/shelter/images-backfill \
    --state ~/collector-data/image-backfill-state.json \
    --metrics ~/collector-data/image-backfill-metrics.json
```

- 범위 결정(2026-09-03): **2024-01 이후** (257,190 레코드 → 실측 대상 466,473장·성공 452,471장·128.9GB, 평균 299KB/장).
  파일은 2022-01 이후만 생존하지만 매칭에 실질적인 최근 구간으로 한정 — 2022~2023년분은 필요 시
  `--from`만 바꿔 추가(완료 월은 상태 파일이 건너뛴다)
- 실행은 서버 2에서 `nohup`/`setsid`로 돈다 — 조작하는 PC를 꺼도 계속되고, 중단되면 청크 단위로 재개.
  진행 로그가 보이게 `python -u`(unbuffered)로 띄운다
- 워커(`--workers`, 기본 8)는 동시 HTTP 연결 수 — 상한은 우리 성능이 아니라 원본 서버 부하·차단 위험으로 정한다.
  벌크는 16까지 실측 무리 없음(17~30장/초), 일일 증분 imager는 8 유지
- 적재: `<hdfs-dir>/yyyymm=YYYYMM/images-YYYYMM-NNNN.tar` + `missing-YYYYMM-NNNN.jsonl`. 엔트리명·포맷
  규칙은 증분 이미지와 동일(`{desertionNo}_{1|2}.{jpg|png}`, 매직 바이트 판별)
- **복제 1**(`--replication`, 기본 1): 원본 서버에서 다시 받을 수 있는 파생물이라 HDFS 용량을 아낀다.
  증분 이미지·레코드는 클러스터 기본 복제(2)를 따른다
- 청크는 엔트리 키 정렬 후 분할해 결정적 — 재실행 시 같은 청크 번호를 받아 재개가 성립한다.
  상태는 HDFS 업로드 성공 후에만 확정(실패 청크는 다음 실행이 다시 받는다)
- 지표(`--metrics`): 월별 대상·성공·결손·바이트·소요, 결손 사유 분포
- 실행 기록·해석: [data/experiments/2026-09-04-image-backfill](../experiments/2026-09-04-image-backfill/README.md)
  (33개월 452,471장 128.9GB, 결손 3.0% 거의 404, 개체당 2장은 2024-06부터, 워커 16 기준 17~30장/초)

## 이미지 결손 재시도 (retry_missing.py)

`missing-*.jsonl` 중 **일시 오류(타임아웃·연결 리셋)만** 다시 내려받아 같은 파티션에 `images-<라벨>-retry.tar`(+ 여전히 실패한 건은
`missing-<라벨>-retry.jsonl`)로 남긴다. 404·포맷 불명은 다시 받아도 같으므로 건너뛰고, 원본 missing 파일은 기록 보존을 위해 손대지 않는다.
첫 실행(2026-09-08) 실측: 백필 결손 14,002건 중 재시도 대상 116 → 회복 16, 나머지 100은 재시도 시점에 404(원본 삭제 확정).

```bash
# 서버 2 — 전체 파티션 (또는 --partitions yyyymm=202409 ...)
python retry_missing.py --hdfs-dir /data/shelter/images-backfill
# 새로 생긴 tar 만 임베딩
TAR_GLOB='images-*-retry.tar' bash ~/embedding-app/hdfs_embed_partition.sh /data/shelter/images-backfill/yyyymm=202409 shelter-backfill cpu
```

## 보호소 결과 통계 (shelter_outcomes.py)

홈 카드 "보호소에 들어온 동물은 어떻게 되나"(D3 `shelterOutcomes`)의 배치다. 백필 `yyyymm=` 파티션 중 창에 걸치는 달을
WebHDFS 로 스트리밍해 **최근 3년, 최근 60일 제외, `종료(*)` 건만** 집계한다 — 아직 `보호중`인 건이 분모에 들어가면 반환율이
깎여 보이고, 18년 평균은 분류가 흘러와 쓸 수 없다(2026-09-14 결정). 결과는 `dashboard_stat('shelter_outcomes','00000')` 한 행에
jsonb 로 upsert 하고 백엔드는 읽기만 한다. 종결 건이 0 이면 DB 를 건드리지 않고 종료 코드 2.

payload: `windowStart/End`, `closedCount`, `returnCount`·`adoptionCount`·`returnRate`·`adoptionRate`, `averageNoticeDays`(noticeSdt→Edt),
`byState`(안락사 포함 — 카드 헤드라인으로는 쓰지 않는다), `byYear`, `coverageStart/EndMonth`, `skipped`.

백필은 일회성이라 새 달이 자동으로 붙지 않는다. 창의 끝이 마지막 백필 달을 넘으면 `coverageEndMonth` 가 뒤처진 채 계산된다 —
분기마다 `backfill.py` 로 최근 달을 다시 받아 두면 된다. 실측(2026-09-15, 서버 1): 아래 deploy-guide 참조.

## 일회성 조사 도구 (tools/)

수집·적재 인터페이스와 무관한 읽기 전용 조사 스크립트 — 집계 결과만 출력하며 원시 레코드를 저장하지 않는다.

- `tools/tag_value_survey.py` — 품종·색·체중 원시 값의 고유값·빈도·결측률·표기 변형 분포 (#117)
- `tools/check_vocab_coverage.py` — [data/tags/vocabulary.json](../tags/vocabulary.json) 정규화 매핑의 실데이터 커버리지 측정 (어휘 개정 시 회귀 확인)
- 실행 기록: [data/experiments/2026-09-09-tag-vocabulary](../experiments/2026-09-09-tag-vocabulary/README.md)

## Kafka·적재 계약

- 토픽 `shelter.raw`: 파티션 3 · 복제 1 · 보존 7일 (Kafka는 통로, 영구 저장은 HDFS)
- 메시지: key=`desertionNo`, value=API 원문 JSON — 같은 개체의 갱신은 같은 파티션(순서 보장)
- 적재 경로: `/data/shelter/raw/dt=YYYY-MM-DD/part-<ms>.jsonl` (실행마다 새 파일)
- 이미지: 같은 토픽을 **컨슈머 그룹 `image-collector`가 독립 소비** (팬아웃) →
  `/data/shelter/images/dt=YYYY-MM-DD/images-<ms>.tar` (엔트리 `{desertionNo}_{1|2}.{jpg|png}`,
  매직 바이트로 포맷 판별) + `missing-<ms>.jsonl` (실패 건 — 커버리지 KPI 근거).
  tar인 이유: 소비자가 MapReduce가 아니라 임베딩 서비스(Python)라 범용 포맷이 맞다
- 전달 보장 **at-least-once**: 발행 실패 시 상태 파일을 확정하지 않아 다음 실행이 같은 증분을
  재시도하고, 적재기는 HDFS 업로드 성공 후에만 오프셋을 커밋한다. 따라서 중복이 가능하며
  **하류는 `desertionNo`+`updTm`로 멱등 처리**하는 것이 계약이다
- PostgreSQL 적재기는 별도 consumer group `postgres-public-ingestion`으로 같은 토픽을 읽는다.
  DB 트랜잭션 성공 후에만 offset을 커밋하고, 승인된 행정구역 CSV의 checksum·version 검증을 통과한
  위치만 적재한다. 매핑 불가 원문은 임의 지역으로 보정하지 않고 해당 `ingestion_run.failed_count`에
  비민감 오류 코드로 집계한다.

## 알려진 한계 (부채)

- imager는 배치 전량을 한 번에 소비한 뒤 커밋한다 (`max.poll.interval.ms` 2시간).
  일일 증분(분 단위)·현재 규모 벌크(1.4만 장/55분 실측)에는 충분하다. 수십만 건 역사 이미지는
  Kafka를 태우지 않고 `image_backfill.py`(HDFS 레코드 입력, 청크 재개)가 담당하므로 imager의
  청크 전환은 보류 — 일일 증분이 수만 장 규모로 커질 때 다시 본다

## 공공데이터 이용 조건 (2026-09-07 확인)

- 공공데이터포털 API 페이지(15098931): **이용허락범위 제한 없음**, 무료, 자동승인. 공공누리 유형·사진 관련 별도 문구 없음
- 사진 파일이 서빙되는 animal.go.kr 저작권 정책: 무단 복제·배포 원칙적 금지, "상업적 용도 또는 다량저장,
  재가동 등 자료수집 목적" 사용 불가, 수익 목적은 사전 협의·허락 필요, 허락 시에도 **출처(국가동물보호정보시스템) 명시**,
  내용 무단변경 금지, 링크 시 본부 통지 (관리자 054-912-0525)
- 두 규정이 충돌한다(API는 제한 없음, 파일 서버는 다량 저장 금지). 어느 쪽이 우선인지는 기관 확인이 필요하며
  **팀원이 관리자에게 전화로 확인**하는 것이 남은 일이다. 확인 전까지 지키는 최소선:
  - 앱 화면에 보호소 사진을 노출할 때 **"출처: 국가동물보호정보시스템(농림축산검역본부)"** 표기
  - 노출용 사진은 변형(크롭·필터)하지 않는다 — 크롭·마스킹은 임베딩 계산 내부에서만
  - 수집한 사진은 매칭 계산 용도로만 저장하고 외부 재배포하지 않는다

## 설계 메모

- 레코드는 **원문 그대로** 저장한다 — 스키마 해석·정제는 하류(적재·매칭)의 몫
- 수집 범위는 축종 필터 없이 전체 (버리는 건 하류에서 가능, 다시 모으는 건 불가능)
- 네트워크 오류는 지수 백오프로 3회 재시도, 인증 오류(resultCode≠00)는 즉시 실패
- 이미지 URL(`popfile1·2`)은 이 단계에서 다운로드하지 않는다 — 이미지 수집기(후속 MR)가 담당.
  실측상 URL 확장자와 실제 포맷이 다른 경우(~2.5% PNG)가 있어 매직 바이트 판별이 필요하다
