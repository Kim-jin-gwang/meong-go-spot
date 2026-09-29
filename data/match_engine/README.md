# 매칭 엔진 (data/match_engine)

`docs/data-ai-interface.md` §후보 출력 정책을 계산하는 **상주 HTTP 서비스**다. worker(`data/matching_worker`)가
`match_run` 을 선점해 `MATCH_ENGINE_URL` 로 POST 하면 후보 Top-K 를 정렬해 돌려준다 (#144).

```text
worker ──POST /match──▶ engine ──WebHDFS OPEN──▶ 질의 사진 + 사용자 후보 사진 (HDFS /data/user/…)
                          │        ──PostgreSQL──▶ 후보 (SHELTERING — 공공 PUBLIC + 사용자 보호 게시물 USER)
                          │        ──메모리──────▶ 스냅샷 벡터 (공공, 매일 22:30 뒤 자동 교체) + 사용자 사진 임베딩 캐시
                          ◀── {matchRunId, modelId, modelVersion, candidates[≤20]}
```

| 단계 | 규칙 | 근거 |
|---|---|---|
| 후보 좁히기 | `case_type=SHELTERING, status=ACTIVE, is_matchable`, `species` 같음, `event_date ≥ 실종일`, `EVENT` 위치의 `region_code` 앞 N자리 같음(기본 2 = 시·도). 출처는 둘: `PUBLIC`(공공 입소)과 `USER`(사용자 보호 게시물 — 삭제 안 됨. 작성자 `ACTIVE` 는 엔진이 아니라 표시 계층이 검사한다 — 엔진 계정은 `member`·`user_post` 를 못 읽는다) | 계약 0·0-0 (URS UR-OWN-004). 같은 정답쌍에서 전국 25만 개체 recall@1 0.51 vs 100마리 0.81 — 좁히기가 벡터 검색보다 먼저 품질을 정한다 |
| 사용자 후보 벡터 | 스냅샷에 없으므로 매칭 시점에 질의 사진과 같은 경로로 임베딩, `animal_photo.id` 로 프로세스 메모리 캐시(사진 교체 P5 는 새 id). 읽기·임베딩 실패 사진은 그 실행에서만 건너뛴다 | 계약 0-0. 배치·백엔드 무변경으로 등록 직후부터 후보가 된다 |
| 동물 점수 | 질의 사진 × 후보 사진 쌍 코사인의 **최댓값**. 폴백 사진은 빼지도 깎지도 않는다 | 계약 0-2 (2026-09-14 실측 확정) |
| 임계값 | `MATCH_ENGINE_THRESHOLD` = **0.60** (`dinov2_vitb14` v2) | 계약 0-3. 모델이 바뀌면 `data/eval/eval_aggregation.py` 로 다시 정한다 |
| 정렬·K | `total_score DESC, target_case_id ASC`, 상위 20, `rank` 1부터 | 계약 2·3 |
| `total_score` | MVP 에서 `image_score` 와 같다. `distance_km` 은 `null`(공공 케이스에 좌표 없음), `time_gap_days` = 발견일 − 실종일 | 거리·시간 가중은 정의된 것이 없다 |

## 파일

| 파일 | 역할 |
|---|---|
| `engine.py` | 설정(`MATCH_ENGINE_*`) · 스냅샷 적재/교체(`SnapshotStore`) · 후보 조회(`CandidateRepository` — 공공 `find`·사용자 `find_user`) · 질의·사용자 후보 임베딩(`PipelineEmbedder`, `UserPhotoVectors` 캐시) · 점수(`score_public`·`score_user`·`rank_rows`) · HTTP(`/match`, `/health`) |
| `tests/test_engine.py` | 설정 기본값, 벡터 ID 규칙, 후보 묶기(공공·사용자), 점수·임계값·정렬·K, 사용자 후보 캐시·실패 격리, 스냅샷 검증·교체, 요청 검증, 서비스 응답 계약 — torch·DB·네트워크 없이 |
| `../../infra/systemd/match-engine.service` | 서버 1 상주 유닛 (`127.0.0.1:8091`, CPU 3코어·Nice 5·MemoryMax 6G) |
| `../../infra/scripts/setup-match-db.sh` | 읽기 전용 `match_engine` 역할과 `match_worker` 역할·DSN 파일 |

## 설정 (환경 변수)

| 변수 | 기본 | 뜻 |
|---|---|---|
| `MATCH_ENGINE_POSTGRES_DSN` | (필수, Secret 파일) | 후보 조회용 읽기 전용 DSN. 연결이 끊기면(`OperationalError`·`InterfaceError`) 새로 붙어 한 번 재시도한다 — 2026-09-18 PostgreSQL 컨테이너 재생성 뒤 죽은 연결로 5일간 전부 실패한 장애의 재발 방지 |
| `MATCH_ENGINE_SNAPSHOT_DIR` | (필수) | 로컬 스냅샷 디렉터리. 없으면 기동 때 HDFS 에서 내려받는다 |
| `MATCH_ENGINE_WEBHDFS` | `http://bd-master:9870` | NameNode WebHDFS. 질의 사진과 스냅샷을 읽는다 |
| `MATCH_ENGINE_HDFS_USER` | `ubuntu` | WebHDFS `user.name`. backend 의 `PHOTO_HDFS_USER` 와 같아야 0600 사용자 사진이 읽힌다(없으면 익명 → 403) |
| `MATCH_ENGINE_EMBEDDINGS_ROOT` | `/embeddings` | `{root}/ACTIVE` 포인터 → `{root}/{model}/{ver}/snapshot/` |
| `MATCH_ENGINE_AI_DIR` | `/home/ubuntu/ai` | `app/pipeline.py`·`model.yaml`·`models/` |
| `MATCH_ENGINE_MODEL_ID` / `_VERSION` | `dinov2_vitb14` / `v2` | 요청·스냅샷·`model.yaml`·`ACTIVE` 포인터가 전부 이 값이어야 한다 — 하나라도 다르면 기동 거부 또는 409 |
| `MATCH_ENGINE_THRESHOLD` | `0.60` | 계약 0-3 |
| `MATCH_ENGINE_TOP_K` | `20` | 계약 K, 20 초과 불가 |
| `MATCH_ENGINE_REGION_PREFIX_LEN` | `2` | 후보 지역 접두 자릿수. `0` 이면 전국 |
| `MATCH_ENGINE_SNAPSHOT_REFRESH_SECONDS` | `600` | HDFS `meta.json` 의 `built_at` 을 확인하는 주기 |
| `MATCH_ENGINE_BIND` / `_PORT` | `127.0.0.1` / `8091` | worker 와 같은 서버라 루프백만 듣는다 |

## HTTP

- `POST /match` — worker 의 payload(`matchRunId, queryCaseId, queryCaseVersion, modelId, modelVersion, species, eventDate, regionCode, photos[{photoId, storageType, storageUri}]`). 사진은 `storageType=USER_UPLOAD` 에 `/data/user/` 아래 경로만 받는다(질의는 사용자 LOST 게시물). 응답은 worker README 의 계약.
- `GET /health` — `{"status":"ok","snapshot":{"count","builtAt"},"threshold",…}`. 스냅샷이 아직 없으면 503.
- 오류: 400(검증) · 409(`MODEL_MISMATCH`) · 413 · 502(`PHOTO_UNAVAILABLE` — 질의 사진을 HDFS 에서 못 읽음) · 500. worker 는 200 이 아니면 `MATCH_FAILED` 로 적는다.
- 요청은 한 번에 하나만 처리한다 — 임베딩이 CPU 를 다 쓰고 worker 도 순차다.

## 로그에 남기지 않는 것

요청 payload·사진 경로·오류 원문. 남기는 것은 `run=<id> 사진 N장 후보 M마리 → K건 (읽기 s 임베딩 s 전체 s)` 와 예외 종류 이름.

## 실행 (서버 1)

```bash
# 1회: 역할·DSN 파일
sudo bash setup-match-db.sh
# 스냅샷만 먼저 내려받기 (1.4GB, 첫 설치)
MATCH_ENGINE_POSTGRES_DSN=x MATCH_ENGINE_SNAPSHOT_DIR=/home/ubuntu/match-engine/snapshot \
  /home/ubuntu/ai/.venv/bin/python /home/ubuntu/match-engine/engine.py --fetch-only
sudo systemctl enable --now match-engine.service matching-worker.service
curl -s http://127.0.0.1:8091/health
journalctl -u match-engine -n 20 --no-pager
```

## 검증

```bash
python -m pytest data/match_engine/tests -q
npm run check:data
```
