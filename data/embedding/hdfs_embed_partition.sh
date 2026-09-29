#!/usr/bin/env bash
# 서버 2용 래퍼 — HDFS 파티션 하나(예: images-backfill/yyyymm=202401, images/dt=2026-09-07)의 tar를
# 로컬로 내려 bulk_embed.py 를 돌리고 결과를 임베딩 저장 계약 경로에 올린다.
#
#   hdfs_embed_partition.sh <hdfs 입력 파티션> <source> [device]
#   예) hdfs_embed_partition.sh /data/shelter/images/dt=2026-09-07 shelter-daily cpu
#   환경변수: ALLOW_EMPTY=1  — 파티션이 없거나 tar 가 없으면 오류 대신 정상 종료 (일일 DAG: 새 사진이 없는 날)
#             TAR_GLOB=...   — 처리할 tar 이름 패턴 (기본 images-*.tar, 결손 재시도 tar 만: images-*-retry.tar)
#
# 출력: /embeddings/{model_id}/{model_version}/<source>/<파티션명>/vectors-*.tsv, detections-*.jsonl
#       /embeddings/{model_id}/{model_version}/_meta.json (없으면 생성, 있으면 러너가 일치 검사)
# model_id/version 은 ~/ai/model.yaml 에서 읽는다 — 배치·온디맨드가 같은 원천을 보는 것이 계약(§4 ④).
set -euo pipefail

IN_PART="${1:?HDFS 입력 파티션 경로}"
SOURCE="${2:?source (shelter-backfill | shelter-daily)}"
DEVICE="${3:-cpu}"
TAR_GLOB="${TAR_GLOB:-images-*.tar}"
ALLOW_EMPTY="${ALLOW_EMPTY:-0}"
AI_DIR="${AI_DIR:-$HOME/ai}"
PY="${PY:-$AI_DIR/.venv/bin/python}"
RUNNER="${RUNNER:-$HOME/embedding-app/bulk_embed.py}"
WORK="${WORK:-$HOME/embedding-work}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$PATH:/opt/hadoop/bin"

MODEL_ID=$(grep -E '^model_id:' "$AI_DIR/model.yaml" | awk '{print $2}' | tr -d '"' | tr -d "'")
MODEL_VER=$(grep -E '^model_version:' "$AI_DIR/model.yaml" | awk '{print $2}' | tr -d '"' | tr -d "'")
PART_NAME=$(basename "$IN_PART")
OUT_HDFS="/embeddings/$MODEL_ID/$MODEL_VER/$SOURCE/$PART_NAME"
LOCAL_IN="$WORK/in/$PART_NAME"
LOCAL_OUT="$WORK/out/$MODEL_ID-$MODEL_VER"   # 모델 버전당 하나 — _meta.json 일치 검사 단위
STATE="$WORK/state-$SOURCE.json"
METRICS="$WORK/metrics-$SOURCE.json"

mkdir -p "$LOCAL_IN" "$LOCAL_OUT"
echo "[1/3] HDFS → 로컬: $IN_PART"
# tar 가 없는 파티션: 벌크에서는 상류(수집) 문제라 멈추고, 일일 DAG 에서는 "오늘 새 사진 없음"이 정상이라 ALLOW_EMPTY=1 로 통과시킨다
if ! hdfs dfs -ls "$IN_PART/$TAR_GLOB" >/dev/null 2>&1; then   # 글롭은 HDFS 가 전개 — 로컬 셸 전개 방지를 위해 전체를 인용
    if [ "$ALLOW_EMPTY" = "1" ]; then echo "입력 파티션에 $TAR_GLOB 가 없음 — 처리할 사진 없음 (정상 종료): $IN_PART"; exit 0; fi
    echo "입력 파티션에 $TAR_GLOB 가 없습니다: $IN_PART" >&2; exit 1
fi
hdfs dfs -get -f "$IN_PART/$TAR_GLOB" "$LOCAL_IN"/

echo "[2/3] 임베딩 ($DEVICE): $(ls "$LOCAL_IN"/$TAR_GLOB | wc -l)개 tar"
"$PY" -u "$RUNNER" --tars "$LOCAL_IN" --out "$LOCAL_OUT" --state "$STATE" --metrics "$METRICS" \
    --device "$DEVICE" --ai-dir "$AI_DIR"

echo "[3/3] 로컬 → HDFS: $OUT_HDFS"
hdfs dfs -mkdir -p "$OUT_HDFS"
for tar in "$LOCAL_IN"/$TAR_GLOB; do
    [ -f "$tar" ] || continue                       # 글롭 무일치(문자열 그대로) 방어
    stem=$(basename "$tar" .tar)
    # 재실행으로 러너가 건너뛴 tar 는 이번 회차 출력이 없을 수 있다 — 있는 것만 올린다
    [ -f "$LOCAL_OUT/vectors-$stem.tsv" ] || { echo "  건너뜀(출력 없음): $stem"; continue; }
    hdfs dfs -put -f "$LOCAL_OUT/vectors-$stem.tsv" "$LOCAL_OUT/detections-$stem.jsonl" "$OUT_HDFS/"
done
# 정상 이미지가 한 장도 없으면 _meta.json 이 생기지 않는다 — 있을 때만, HDFS 에 없을 때만 올린다
if [ -f "$LOCAL_OUT/_meta.json" ]; then
    hdfs dfs -test -e "/embeddings/$MODEL_ID/$MODEL_VER/_meta.json" \
        || hdfs dfs -put "$LOCAL_OUT/_meta.json" "/embeddings/$MODEL_ID/$MODEL_VER/_meta.json"
fi
rm -rf "$LOCAL_IN"
echo "완료: $OUT_HDFS"
