"""클러스터 상태 점검 DAG — 매일 09:00 KST, 임계치 밖이면 Mattermost 로 알린다.

2026-09-07 서버 1이 단독 재부팅된 뒤 워커(DataNode·NodeManager)가 하루 넘게 죽어 있었는데 아무도 몰랐다.
이 DAG 는 그 하루를 아침 한 번의 알림으로 줄인다. 점검 항목·임계치·파싱은 cluster_check.py(순수 모듈, CI 테스트)에 있고,
여기서는 스케줄(월~금 09:00 KST)과 발송만 둔다 — 매 실행 상태 한 줄, 임계치 위반이면 경보 형식.
collector_daily 실패는 실패 콜백이 즉시 알리므로 여기서 다루지 않는다.
**즉시 경보**는 이 DAG 가 아니라 systemd 타이머(`cluster_check.py --watch`, 5분 간격, data/airflow/systemd/)가 맡는다 —
이 DAG 는 "아침에 한 줄 보는" 요약이고, 문제가 나면 타이머가 먼저 알린 뒤라 09:00 에는 같은 내용이 한 번 더 보일 수 있다.
"""

from __future__ import annotations

import datetime

import pendulum
from airflow import DAG
from airflow.operators.python import PythonOperator

import alerts
import cluster_check


def check() -> str:
    dfs, yarn_running, disk_used_pct = cluster_check.collect()
    problems = cluster_check.evaluate(dfs, yarn_running, disk_used_pct)
    text = cluster_check.status_message(dfs, yarn_running, disk_used_pct, problems)
    print(text)
    alerts.post(text)  # 월~금 09:00 마다 상태 한 줄 (경보든 정상이든) — 채널 소음 대신 매일 아침 확인 습관
    if problems:
        raise alerts.AlreadyAlertedError("클러스터 점검 임계치 위반: " + "; ".join(problems))  # UI 에는 실패로, 알림은 위 경보 1회만
    return text


with DAG(
    dag_id="cluster_health",
    description="Hadoop·HDFS·디스크 상태 점검 → 임계치 밖이면 Mattermost 경보",
    schedule="0 9 * * 1-5",  # 월~금 09:00 KST (사용자 요청 2026-09-08)
    start_date=pendulum.datetime(2026, 9, 8, tz="Asia/Seoul"),
    catchup=False,
    # 점검 스크립트 자체가 죽어도(hdfs 명령 타임아웃 등) 알림이 가야 한다 — 상태 메시지 전에 터지는 경우 대비
    default_args={"retries": 0, "execution_timeout": datetime.timedelta(minutes=5), "on_failure_callback": alerts.notify_failure},
    tags=["ops"],
) as dag:
    PythonOperator(task_id="check_cluster", python_callable=check)
