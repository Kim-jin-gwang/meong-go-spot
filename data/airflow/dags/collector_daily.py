"""일일 수집 파이프라인 DAG — 수집·발행 → 분실 스냅샷 → HDFS 적재 → 이미지 적재 → 임베딩 → 색인 배정 → 서빙 스냅샷.

서버 2(bd-master)의 Airflow(venv, SequentialExecutor)에서 매일 22:30 KST 실행.
각 태스크는 data/collector의 CLI를 그대로 호출한다 — 파이프라인 로직은 collector에,
오케스트레이션(순서·재시도·기록)만 여기에 둔다.

배포: 이 파일을 서버의 ~/airflow/dags/ 에 복사 (docs/bigdata-cluster.md 참조).
"""

import datetime

import pendulum
from airflow import DAG
from airflow.operators.bash import BashOperator

import alerts  # 같은 dags/ 디렉터리 — 실패·완료 Mattermost 알림 (MATTERMOST_WEBHOOK_URL 없으면 로그만)

APP = "/home/ubuntu/collector-app"
DATA = "/home/ubuntu/collector-data"
VENV_PY = "/home/ubuntu/collector-venv/bin/python"
EMBED_APP = "/home/ubuntu/embedding-app"
EMBED_PY = "/home/ubuntu/ai/.venv/bin/python"  # numpy 가 있는 venv (collector-venv 에는 없다)
# hdfs CLI는 로그인 셸이 아니면 PATH에 없으므로 태스크가 직접 환경을 구성한다
HADOOP_ENV = (
    "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 "
    "HADOOP_HOME=/opt/hadoop PATH=$PATH:/opt/hadoop/bin"
)

with DAG(
    dag_id="collector_daily",
    description="구조동물 수집 → Kafka → HDFS (메타데이터·이미지)",
    # 22:30 KST — updTm 실측(2026-09-01, n=6,807)상 입력의 ~98.5%가 21시 전 종료.
    # 심야 꼬리(~1.5%)는 다음날 증분이 흡수. 야간 매칭 배치(3단계)가 돌 자리를 확보한다.
    schedule="30 22 * * *",
    start_date=pendulum.datetime(2026, 9, 1, tz="Asia/Seoul"),
    catchup=False,  # 밀린 과거 날짜를 소급 실행하지 않는다 — 증분 수집은 최신 1회면 충분
    default_args={
        "retries": 2,
        "retry_delay": datetime.timedelta(minutes=10),
        # SequentialExecutor라 태스크 하나가 걸리면 전체가 막힌다 — 기본 시한 필수
        "execution_timeout": datetime.timedelta(minutes=30),
        # 재시도가 모두 소진돼 태스크가 최종 실패할 때 1회 알림 (2026-09-06 API 장애가 하루 뒤 발견된 뒤 추가)
        "on_failure_callback": alerts.notify_failure,
    },
    tags=["pipeline", "stage-2"],
) as dag:
    collect = BashOperator(
        task_id="collect_and_publish",
        bash_command=(
            f"set -a && . /home/ubuntu/collector.env && set +a && "
            f"cd {APP} && {VENV_PY} main.py "
            f"--out {DATA}/out --state {DATA}/state.json --kafka bd-master:9092"
        ),
    )

    snapshot_lost = BashOperator(
        task_id="snapshot_lost_reports",
        # 분실동물 API 는 최근 1개월치만 주고 과거 조회가 없다 — 오늘 안 받으면 영원히 없는 데이터라 매일 전량을
        # /data/lost/raw/dt=오늘/ 에 남긴다(연락처·상세주소 제거). 서비스 DB 적재는 BE 스키마 뒤(-145).
        # 2~4페이지·수 초짜리라 수집 직후에 둔다. 실패해도 다음 날 스냅샷이 같은 신고를 다시 담는다(1개월 창).
        # 품종 사전은 레포의 infra/reference/breed-species.csv 를 서버 {APP}/reference/ 로 복사한 것이다 — 서버에는
        # 레포 트리가 없고 collector 파일만 평평하게 배포한다(docs/bigdata-cluster.md "서버 상주물"). 없으면 species 만 빠진다.
        bash_command=(
            f"set -a && . /home/ubuntu/collector.env && set +a && {HADOOP_ENV} && cd {APP} && "
            f"{VENV_PY} lost_snapshot.py --hdfs-dir /data/lost/raw --breed-species {APP}/reference/breed-species.csv"
        ),
        execution_timeout=datetime.timedelta(minutes=10),
    )
    load_records = BashOperator(
        task_id="load_records_to_hdfs",
        bash_command=(
            f"{HADOOP_ENV} && cd {APP} && "
            f"{VENV_PY} loader.py --bootstrap bd-master:9092 --hdfs-dir /data/shelter/raw"
        ),
    )

    load_images = BashOperator(
        task_id="load_images_to_hdfs",
        bash_command=(
            f"{HADOOP_ENV} && cd {APP} && "
            f"{VENV_PY} imager.py --bootstrap bd-master:9092 --hdfs-dir /data/shelter/images"
        ),
        # 이미지 다운로드는 오래 걸릴 수 있다 — imager 자체의 max.poll 상향과 짝
        execution_timeout=datetime.timedelta(hours=2),
    )

    embed_images = BashOperator(
        task_id="embed_daily_images",
        # 그날 imager 가 만든 파티션(dt=오늘, UTC 날짜 = 22:30 KST 실행 시 같은 날)을 CPU 로 벡터화해
        # /embeddings/{model}/{version}/shelter-daily/dt=.../ 에 적재 — 인덱스가 첫날부터 낡지 않게.
        # 새 사진이 없는 날은 ALLOW_EMPTY 로 정상 종료. ~1,100장 × 1.1초 ≈ 20분(서버 2 CPU 실측).
        bash_command=(
            "ALLOW_EMPTY=1 bash /home/ubuntu/embedding-app/hdfs_embed_partition.sh "
            "/data/shelter/images/dt={{ data_interval_end | ds }} shelter-daily cpu"
        ),
        execution_timeout=datetime.timedelta(minutes=90),
    )

    assign_new = BashOperator(
        task_id="assign_new_vectors",
        # 색인은 백필만 보고 만들어졌다. 새 벡터에 무리 딱지를 붙이지 않으면 색인 기반 검색에서 영원히 빠진다
        # (2026-09-10 발견: 3일치 4,175장이 색인 밖). 중심 256개와의 내적이라 1,100장에 몇 초.
        bash_command=(
            f"{HADOOP_ENV} && cd {EMBED_APP} && "
            # --partition 을 주지 않는다: 배정 파일이 없는 파티션을 스스로 찾아 처리하므로
            # 하루 실패해도 다음 실행이 주워간다 (주면 그날 하루만 보고 실패분은 영구 누락)
            f"{EMBED_PY} index_tools.py assign --source shelter-daily --allow-empty"
        ),
        execution_timeout=datetime.timedelta(minutes=15),
    )

    snapshot = BashOperator(
        task_id="build_vector_snapshot",
        # 서빙(상주 worker)이 시작할 때 읽을 이진 스냅샷. TSV 파싱 65초를 3초로 줄인다.
        # 전량 재작성이라 매일 같은 결과가 나온다(멱등) — 증분 이어붙이기의 실패 경로를 만들지 않기 위한 선택.
        bash_command=f"{HADOOP_ENV} && cd {EMBED_APP} && {EMBED_PY} index_tools.py snapshot",
        execution_timeout=datetime.timedelta(minutes=30),
        on_success_callback=alerts.notify_success,  # 파이프라인 완료 보고는 마지막 태스크에서
    )

    # PostgreSQL 적재(postgres_loader.py)는 이 DAG 에 두지 않는다. 서비스 PostgreSQL 은 서버 1 의
    # 루프백에만 열려 있어(compose.prod.yml) 서버 2 의 Airflow 에서는 닿지 않는다. 서버 1 의
    # shelter-loader.timer 가 22:30 수집 뒤에 실행한다 (infra/systemd/, docs/deploy-guide.md).
    collect >> snapshot_lost >> load_records >> load_images >> embed_images >> assign_new >> snapshot
