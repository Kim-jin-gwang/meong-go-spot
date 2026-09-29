from pathlib import Path

DAG_SOURCE = (Path(__file__).parents[1] / "dags" / "collector_daily.py").read_text(encoding="utf-8")
REPO = Path(__file__).parents[3]


def test_dag_does_not_load_postgres() -> None:
    """서비스 PostgreSQL 은 서버 1 루프백에만 열려 있어 서버 2 의 Airflow 에서 닿지 않는다.

    같은 consumer group(``postgres-public-ingestion``)을 쓰는 실행이 둘이 되면 파티션이 갈려
    한쪽이 빈손으로 끝나므로, 여기에 되살리면 안 된다.
    """
    # 주석으로는 "여기 두지 않는다"고 설명하므로 실행되는 코드 줄만 본다.
    code = [line for line in DAG_SOURCE.splitlines() if not line.lstrip().startswith("#")]
    assert not [line for line in code if "postgres_loader" in line]
    assert not [line for line in code if "persist_public_records" in line]


def test_loader_records_the_collected_day_in_kst() -> None:
    """적재 실행 이력(ingestion_run)의 요청 구간은 수집 날짜여야 한다.

    23:10 KST 실행이 자정을 넘겨도 같은 날짜를 기록해야 하므로 UTC 날짜를 쓰면 안 된다.
    """
    wrapper = (REPO / "infra" / "scripts" / "run-shelter-loader.sh").read_text(encoding="utf-8")
    assert "TZ=Asia/Seoul date +%F" in wrapper
    assert '--requested-from "$day"' in wrapper
    assert '--requested-to "$day"' in wrapper


def test_timer_runs_after_the_collector_dag() -> None:
    timer = (REPO / "infra" / "systemd" / "shelter-loader.timer").read_text(encoding="utf-8")
    assert 'schedule="30 22 * * *"' in DAG_SOURCE, "수집 시각이 바뀌면 타이머도 같이 옮긴다"
    assert "OnCalendar=*-*-* 23:10:00 Asia/Seoul" in timer


def test_lost_snapshot_runs_right_after_collection_and_before_the_success_report() -> None:
    """완료 알림은 마지막 태스크(build_vector_snapshot)에서 나가므로, 분실 스냅샷 건수가 요약에 들려면 그 앞 체인에 있어야 한다."""
    assert 'task_id="snapshot_lost_reports"' in DAG_SOURCE
    assert "collect >> snapshot_lost >> load_records" in DAG_SOURCE
    assert "lost_snapshot.py --hdfs-dir /data/lost/raw" in DAG_SOURCE
