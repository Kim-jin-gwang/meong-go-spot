# 임베딩 (data/embedding)

사진 tar → AI 파이프라인(`ai/app/pipeline.py`: YOLO26 크롭 → DINOv2 768차원) → 벡터 파일. 저장 계약은
[docs/data-ai-interface.md](../../docs/data-ai-interface.md) §3 "임베딩 저장 계약"이 원천이고, 이 문서는 실행 방법이다.

## 구성

| 파일 | 역할 | 실행 위치 |
|---|---|---|
| `bulk_embed.py` | 로컬 tar 디렉터리 → `vectors-*.tsv` + `detections-*.jsonl` + `_meta.json`. tar 단위 재개, 모델 버전 혼입 거부 | 어디서든 (ai/ venv 필요) |
| `hdfs_embed_partition.sh` | HDFS 파티션 하나를 내려 러너 실행 후 계약 경로에 업로드 — **일일 증분(서버 2 CPU)** 용. `ALLOW_EMPTY=1`(tar 없으면 정상 종료, DAG용), `TAR_GLOB=images-*-retry.tar`(결손 재시도 tar만) | 서버 2 |
| `index_tools.py` | **서빙 산출물 관리** (2026-09-10) — `snapshot`: 전체 벡터를 이진 `snapshot/vectors-float32.npy`+`ids.txt`+`meta.json`으로 (worker 로딩 65초 → 0.4초). `assign`: 새 파티션 벡터에 K-Means 무리 번호를 붙여 `index/kmeans-k{K}/assignments/`에. `--partition` 을 주지 않으면 **배정 파일이 없는 파티션을 스스로 찾아** 처리하므로 하루 실패해도 다음 실행이 주워간다. 중심 지문(`_centers.sha256`)을 대조해 색인이 재구축됐으면 거부한다. 둘 다 DAG 태스크 | 서버 2 |
| `gpu_embed_month.sh` | 서버 2에서 월별 tar를 scp로 끌어와 GPU로 임베딩, 결과를 되돌려 HDFS 적재 — **벌크(GPU 서버)** 용 | GPU 서버 |
| `tests/` | 모의 파이프라인으로 러너 계약 검증 (torch 불필요, CI `data-check`) | CI·로컬 |

러너는 HDFS·scp를 모른다. 이동은 래퍼가 맡고, 러너는 "로컬 tar → 로컬 파일"만 한다. 그래서 서버 2·GPU 서버·CI에서
같은 코드가 돌고, 테스트는 모의 파이프라인을 주입해 torch 없이 계약(형식·재개·버전 격리)을 검증한다.

## 출력 형식

- `vectors-<tar stem>.tsv`: `사진ID<TAB>v1,v2,...,v768` (소수 6자리) — MapReduce 5종의 입력 형식과 동일해 변환 없이 K-Means·kNN 조인 입력이 된다
- `detections-<tar stem>.jsonl`: 사진별 `{photo_id, fallback, confidence, box, detection_count}` 또는 `{photo_id, error}`(깨진 파일)
- `snapshot/vectors-float32.npy` + `ids.txt` + `meta.json`: 같은 숫자의 **이진 사본** — 배치는 TSV, 서빙은 npy를 읽는다. 하둡은 npy를 쪼갤 수 없으므로 MapReduce 입력이 아니다
- `index/kmeans-k{K}/assignments/{base.tsv, shelter-daily-dt-*.tsv}`: `사진ID<TAB>무리번호` — 소비자는 디렉터리 전체를 읽고 중복 ID는 마지막 것을 쓴다
- `_meta.json`: `model_id·model_version·dim·normalized·detector` — 출력 디렉터리당 1개. 다른 값의 벡터를 같은 디렉터리에 쓰려 하면 러너가 거부한다(다른 모델의 벡터는 절대 섞지 않는다)
- 사진 ID = tar 엔트리 stem `{desertionNo}_{1|2}` (원본 이미지 HDFS·ID 계약과 동일)

## 실행

```bash
# 서버 2 — 일일 증분 (collector_daily 4번째 태스크 embed_daily_images 가 실행)
ALLOW_EMPTY=1 bash hdfs_embed_partition.sh /data/shelter/images/dt=2026-09-07 shelter-daily cpu

# 결손 재시도로 생긴 tar 만 임베딩 (retry_missing.py 이후)
TAR_GLOB='images-*-retry.tar' bash hdfs_embed_partition.sh /data/shelter/images-backfill/yyyymm=202409 shelter-backfill cpu

# GPU 서버 — 벌크, 월 단위 (전제: 서버 2로 SSH 가능한 전용 키, ~/ai 코드·가중치)
REMOTE=ubuntu@<server2-host> DEVICE=cuda:1 bash gpu_embed_month.sh 202401 202402 202403

# 로컬 검증 (모의 파이프라인)
python -m pytest data/embedding/tests -q
```

## 처리량 (실측·추정)

| 환경 | 장당 | 일일 증분 1,100장 | 벌크 45만 장 |
|---|---|---|---|
| 서버 2 CPU (4 vCPU, `ai/` 실측 e2e 1.1초) | 1.1초 | 약 20분 → 22:30 배치 안에 들어옴 | 5.7일 — 비현실적 |
| GPU 서버 (추정, 실측 예정) | 0.02~0.05초 | — | 3~6시간 |

## GPU 서버 실행 절차 (2026-09-07 확인)

- 환경: TLJH Python 3.12, 드라이버 570(CUDA 12.8) → `python3 -m venv ~/embed-venv` 후 **torch 2.11.0+cu128**(cu128 인덱스의 최신; 서버 2의 2.14는 cu130 빌드라 불가) + ultralytics 등 `ai/requirements.txt` 나머지 핀
- 코드·가중치·DINOv2 torch hub 캐시는 서버 2 `~/gpu-bundle/` 에서 scp (GitHub 접속 불필요). `model.yaml` 의 절대 경로는 서버 2 기준이지만 러너가 `--ai-dir/models/` 아래 파일을 우선 쓴다
- 장치 지정은 공용 서버 가이드대로 `CUDA_DEVICE_ORDER=PCI_BUS_ID` + `CUDA_VISIBLE_DEVICES=<배정 번호>` — 래퍼가 `GPU_DEVICE`(기본 1)로 설정하고 내부는 `cuda:0`. 확인: `nvidia-smi --query-compute-apps=pid,gpu_bus_id --format=csv` 의 버스가 배정 카드(GPU 1 = `…:61:00.0`)인지
- 서버 2 접속은 GPU 서버 전용 ed25519 키(`~/.ssh/config` Host 항목) — pem 은 복사하지 않는다
- 스모크 실측: 실사진 20장 2.2초(9.1장/초, 워밍업 포함), CPU 결과와 소수 3자리 일치
- **전량 실행 기록**: [data/experiments/2026-09-07-bulk-embedding](../experiments/2026-09-07-bulk-embedding/README.md) — 33개월 452,470 벡터, 폴백 12.1%, 약 20장/초·7시간

## 운영 규칙

- **상태 파일과 로컬 출력 디렉터리는 함께 지운다.** 러너는 `state`의 완료 tar를 건너뛰고, 래퍼는 로컬 출력이 있는 tar만 HDFS에 올린다.
  출력만 지우고 상태를 남기면 그 tar는 다시 계산되지도, 올라가지도 않는다. 재계산이 필요하면 `state`에서 해당 tar 항목을 빼거나 둘 다 지운다
- HDFS에 실제로 올라갔는지는 `hdfs dfs -ls /embeddings/{model_id}/{version}/{source}/{partition}/`의 `vectors-*` 개수와 입력 tar 개수를 대조한다

## 배포 (서버 2)

`~/embedding-app/`에 이 디렉터리를 scp로 두고(`sed -i 's/
$//'`로 CRLF 제거), `~/ai/.venv`를 인터프리터로 쓴다.
