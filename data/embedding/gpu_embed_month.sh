#!/usr/bin/env bash
# GPU 서버용 래퍼 — 서버 2에서 한 달치 tar를 scp 로 끌어와 GPU 로 임베딩하고 결과를 되돌려 HDFS 에 올린다.
# 한 달(3~5GB)씩 처리하고 tar 를 지우므로 GPU 서버 디스크를 거의 쓰지 않는다. 월 목록을 돌려 45만 장을 처리한다.
#
#   전제: GPU 서버 → 서버 2 SSH 가 열려 있고(전용 키 쌍, pem 복사 금지), 서버 2 에 ~/embedding-stage/ 가 있다.
#         GPU 서버에 ai/ 코드·가중치(~/ai)와 이 레포의 data/embedding 이 있다.
#   REMOTE=ubuntu@<server2-host> GPU_DEVICE=1 gpu_embed_month.sh 202401 [202402 ...]   (가상환경 활성화 후)
set -euo pipefail

REMOTE="${REMOTE:?REMOTE 를 서버 2 접속 대상(user@host)으로 지정}"
REMOTE_HDFS_IN="${REMOTE_HDFS_IN:-/data/shelter/images-backfill}"
SOURCE="${SOURCE:-shelter-backfill}"
# GPU 지정 규칙(공용 서버 가이드): PCI 버스 순서로 고정하고 배정된 물리 장치 하나만 보이게 한다.
# 보이는 장치가 하나라 프로세스 안에서는 항상 cuda:0 이다 — cuda:1 을 주면 invalid device ordinal.
export CUDA_DEVICE_ORDER=PCI_BUS_ID
export CUDA_VISIBLE_DEVICES="${GPU_DEVICE:-1}"   # 팀 배정 물리 GPU 번호 (nvidia-smi 기준)
DEVICE="${DEVICE:-cuda:0}"
export YOLO_CONFIG_DIR="${YOLO_CONFIG_DIR:-$HOME/.config/yolo}"   # 기본 경로가 쓰기 불가면 /tmp 로 새는 것을 방지
mkdir -p "$YOLO_CONFIG_DIR"
AI_DIR="${AI_DIR:-$HOME/ai}"
PY="${PY:-python}"
RUNNER="${RUNNER:-$(dirname "$0")/bulk_embed.py}"
WORK="${WORK:-$HOME/embedding-work}"
REMOTE_ENV='export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 PATH=$PATH:/opt/hadoop/bin'

MODEL_ID=$(grep -E '^model_id:' "$AI_DIR/model.yaml" | awk '{print $2}' | tr -d '"' | tr -d "'")
MODEL_VER=$(grep -E '^model_version:' "$AI_DIR/model.yaml" | awk '{print $2}' | tr -d '"' | tr -d "'")
LOCAL_OUT="$WORK/out/$MODEL_ID-$MODEL_VER"
mkdir -p "$LOCAL_OUT"

for MONTH in "$@"; do
    PART="yyyymm=$MONTH"
    LOCAL_IN="$WORK/in/$PART"
    mkdir -p "$LOCAL_IN"
    echo "=== $PART ==="
    echo "[1/4] 서버 2 HDFS → 스테이징 → scp"
    ssh "$REMOTE" "$REMOTE_ENV; hdfs dfs -ls $REMOTE_HDFS_IN/$PART/images-*.tar >/dev/null 2>&1" \
        || { echo "서버 2 HDFS 에 $PART 의 images-*.tar 가 없습니다" >&2; exit 1; }
    ssh "$REMOTE" "$REMOTE_ENV; mkdir -p ~/embedding-stage/$PART && hdfs dfs -get -f $REMOTE_HDFS_IN/$PART/images-*.tar ~/embedding-stage/$PART/"
    scp -q "$REMOTE:~/embedding-stage/$PART/images-*.tar" "$LOCAL_IN"/
    ssh "$REMOTE" "rm -rf ~/embedding-stage/$PART"

    echo "[2/4] 임베딩 ($DEVICE)"
    "$PY" -u "$RUNNER" --tars "$LOCAL_IN" --out "$LOCAL_OUT" --state "$WORK/state-$SOURCE.json" \
        --metrics "$WORK/metrics-$SOURCE.json" --device "$DEVICE" --ai-dir "$AI_DIR"

    echo "[3/4] 결과 → 서버 2 → HDFS"
    OUT_HDFS="/embeddings/$MODEL_ID/$MODEL_VER/$SOURCE/$PART"
    ssh "$REMOTE" "mkdir -p ~/embedding-stage/out-$PART"
    for tar in "$LOCAL_IN"/images-*.tar; do
        [ -f "$tar" ] || continue                   # 글롭 무일치 방어
        stem=$(basename "$tar" .tar)
        # 재실행으로 러너가 건너뛴 tar 는 출력이 없을 수 있다 — 있는 것만 보낸다
        [ -f "$LOCAL_OUT/vectors-$stem.tsv" ] || { echo "  건너뜀(출력 없음): $stem"; continue; }
        scp -q "$LOCAL_OUT/vectors-$stem.tsv" "$LOCAL_OUT/detections-$stem.jsonl" "$REMOTE:~/embedding-stage/out-$PART/"
    done
    [ -f "$LOCAL_OUT/_meta.json" ] && scp -q "$LOCAL_OUT/_meta.json" "$REMOTE:~/embedding-stage/out-$PART/"
    ssh "$REMOTE" "$REMOTE_ENV; hdfs dfs -mkdir -p $OUT_HDFS && (ls ~/embedding-stage/out-$PART/vectors-* >/dev/null 2>&1 && hdfs dfs -put -f ~/embedding-stage/out-$PART/vectors-* ~/embedding-stage/out-$PART/detections-* $OUT_HDFS/ || echo 올릴-벡터-없음) \
        && (hdfs dfs -test -e /embeddings/$MODEL_ID/$MODEL_VER/_meta.json || [ ! -f ~/embedding-stage/out-$PART/_meta.json ] || hdfs dfs -put ~/embedding-stage/out-$PART/_meta.json /embeddings/$MODEL_ID/$MODEL_VER/_meta.json) \
        && rm -rf ~/embedding-stage/out-$PART"

    echo "[4/4] 로컬 tar 정리"
    rm -rf "$LOCAL_IN"
done
echo "완료: $*"
