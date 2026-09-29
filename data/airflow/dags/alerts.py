"""Mattermost 알림 — Airflow 콜백과 상태 점검 스크립트가 공유하는 발송 모듈 (표준 라이브러리만).

웹훅 URL은 환경변수 `MATTERMOST_WEBHOOK_URL`(서버 2 `~/airflow/airflow-local.env`, 600)에서 읽는다.
비어 있으면 로그만 남기고 조용히 넘어간다 — 알림 부재가 파이프라인 실패로 번지면 안 된다.
발송 실패도 예외를 밖으로 내지 않는다 (콜백 안에서 터지면 Airflow 가 태스크 상태 처리 중 죽는다).

어느 채널로 갈지는 웹훅 URL 이 정한다 — 데이터 파이프라인 전용 채널의 인커밍 웹훅을 권장. 메시지 머리에 [DATA] 를 붙인다.
"""

from __future__ import annotations

import datetime
import json
import logging
import os
import re
import urllib.request

LOG = logging.getLogger(__name__)
TIMEOUT_SEC = 10
LOG_HINT = "로그: 서버 2 ~/airflow/logs/dag_id={dag}/run_id={run}/task_id={task}/"


class AlreadyAlertedError(RuntimeError):
    """태스크가 실패로 남아야 하지만 알림은 이미 직접 보낸 경우 — notify_failure 가 중복 발송을 건너뛴다."""


def webhook_url() -> str:
    return os.environ.get("MATTERMOST_WEBHOOK_URL", "").strip()


def post(text: str, url: str | None = None) -> bool:
    """메시지를 보낸다. 성공 True, 웹훅 없음·실패 False (예외를 내지 않음)."""
    url = url if url is not None else webhook_url()
    if not url:
        LOG.warning("MATTERMOST_WEBHOOK_URL 미설정 — 알림 생략: %s", (text.splitlines() or [""])[0][:120])
        return False
    body = json.dumps({"text": text}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SEC) as res:
            return 200 <= res.status < 300
    except Exception as error:  # noqa: BLE001 — 알림 실패는 기록만
        LOG.error("Mattermost 발송 실패: %s", error)
        return False


def failure_message(dag_id: str, task_id: str, run_id: str, try_number: int, max_tries: int,
                    when: datetime.datetime, error: str | None) -> str:
    """실패 알림 본문 — 무엇이·언제·몇 번째 시도에서 죽었고 어디를 보면 되는지."""
    head = f":rotating_light: **[DATA] {dag_id} 실패** — `{task_id}` (시도 {try_number}/{max_tries})"
    lines = [head, f"- 실행: `{run_id}`", f"- 시각: {when.astimezone(datetime.timezone(datetime.timedelta(hours=9))):%Y-%m-%d %H:%M} KST"]
    err_lines = str(error).strip().splitlines() if error else []
    if err_lines:  # 공백만 있는 예외 문자열이면 줄이 없다 — IndexError 로 알림이 통째로 빠지지 않게
        lines.append(f"- 오류: `{err_lines[-1][:200]}`")
    lines.append("- " + LOG_HINT.format(dag=dag_id, run=run_id, task=task_id))
    return "\n".join(lines)


# ── 수집 건수 요약 ───────────────────────────────────────────────────────
#
# BashOperator 는 stdout 의 마지막 줄을 XCom(return_value)으로 올린다. 수집·적재 스크립트가
# 이미 건수를 그 줄에 찍고 있으므로 알림에서 다시 세지 않고 그대로 읽는다.
#
# 다른 모듈의 출력 형식에 기대는 코드다. 형식이 바뀌면 숫자가 조용히 사라지므로
# tests/test_alerts.py 가 실제 스크립트 소스와 함께 검사한다.

COUNT_TASKS = ("collect_and_publish", "snapshot_lost_reports", "load_records_to_hdfs", "load_images_to_hdfs",
               "assign_new_vectors", "build_vector_snapshot")
EMPTY_MARK = "새 메시지 없음"


def _num(pattern: str, text: str) -> int | None:
    """숫자를 뽑는다. index_tools 는 `456,335` 처럼 쉼표를 넣어 찍으므로 걷어낸다."""
    found = re.search(pattern, text)
    if not found:
        return None
    digits = found.group(1).replace(",", "")
    return int(digits) if digits else None


def _collect_line(text: str) -> str | None:
    total = _num(r"수집 ([0-9,]+)건", text)
    if total is None:
        return None
    fresh, changed = _num(r"신규 ([0-9,]+)", text), _num(r"변경 ([0-9,]+)", text)
    published = _num(r"Kafka 발행 ([0-9,]+)건", text)
    line = f"- 수집 {total:,}건"
    if fresh is not None and changed is not None:
        line += f" (신규 {fresh:,} · 변경 {changed:,})"
    if published is not None:
        line += f" · Kafka 발행 {published:,}건"
    return line


def _lost_line(text: str) -> str | None:
    # 분실동물 일일 스냅샷 (lost_snapshot.py) — 신고는 1개월 창이라 150~200건 근처가 정상, 0건은 API 이상 신호
    if "분실 스냅샷: 0건" in text:
        return "- 분실 스냅샷: 0건 (API 빈 목록)"
    total = _num(r"분실 스냅샷 완료: ([0-9,]+)건", text)
    if total is None:
        return None
    line = f"- 분실 스냅샷 {total:,}건"
    dog, cat, unresolved = _num(r"개 ([0-9,]+)", text), _num(r"고양이 ([0-9,]+)", text), _num(r"미상 ([0-9,]+)", text)
    if dog is not None and cat is not None and unresolved is not None:
        line += f" (개 {dog:,} · 고양이 {cat:,} · 미상 {unresolved:,})"
    return line


def _records_line(text: str) -> str | None:
    if EMPTY_MARK in text:
        return "- HDFS 원문: 새 메시지 없음"
    loaded = _num(r"적재 완료: ([0-9,]+)건", text)
    return f"- HDFS 원문 {loaded:,}건" if loaded is not None else None


def _images_line(text: str) -> str | None:
    if EMPTY_MARK in text:
        return "- 이미지: 새 메시지 없음"
    saved = _num(r"성공 ([0-9,]+)", text)
    if saved is None:
        return None
    missing = _num(r"결손 ([0-9,]+)", text)
    line = f"- 이미지 {saved:,}장"
    if missing:  # 0 이면 굳이 적지 않는다
        line += f" (결손 {missing:,})"
    return line


def _assign_line(text: str) -> str | None:
    if "배정할 새 파티션 없음" in text:
        return "- 벡터 배정: 새 파티션 없음"
    assigned = _num(r"합계 ([0-9,]+)개 배정", text)
    return f"- 벡터 배정 {assigned:,}개" if assigned is not None else None


def _snapshot_line(text: str) -> str | None:
    # 서빙 worker 가 기동 때 읽는 산출물 — 전체 갤러리 크기라 하루아침에 줄면 이상 신호다
    rows = _num(r"스냅샷 ([0-9,]+) ×", text)
    if rows is None:
        return None
    line = f"- 스냅샷 {rows:,}개"
    size = _megabytes(text)
    if size is not None:
        line += f" ({size:,}MB)"
    return line


def _megabytes(text: str) -> int | None:
    """`1402MB` 를 읽는다. 소수를 허용하는 이유는 안 하면 `1402.5MB` 에서 `5` 만 잡히기 때문이다 —
    빠지는 것보다 조용히 틀린 숫자가 나가는 쪽이 나쁘다. 형식이 더 바뀌면 크기만 빠지고
    건수는 남는다."""
    found = re.search(r"([0-9,]+(?:[.][0-9]+)?)MB", text)
    if not found:
        return None
    try:
        return round(float(found.group(1).replace(",", "")))
    except ValueError:
        return None


def collection_summary(outputs: dict) -> list:
    """태스크별 마지막 출력에서 건수 줄을 만든다. 못 읽은 태스크는 조용히 건너뛴다."""
    builders = (
        ("collect_and_publish", _collect_line),
        ("snapshot_lost_reports", _lost_line),
        ("load_records_to_hdfs", _records_line),
        ("load_images_to_hdfs", _images_line),
        ("assign_new_vectors", _assign_line),
        ("build_vector_snapshot", _snapshot_line),
    )
    lines = []
    for task_id, build in builders:
        raw = outputs.get(task_id)
        if not raw:
            continue
        try:
            line = build(str(raw))
        except Exception as error:  # noqa: BLE001 — 숫자 하나 때문에 알림을 잃지 않는다
            LOG.warning("%s 출력 해석 실패(무시): %s", task_id, error)
            continue
        if line:
            lines.append(line)
    return lines


def success_message(dag_id: str, run_id: str, started: datetime.datetime | None, finished: datetime.datetime,
                    summary: list | None = None) -> str:
    kst = datetime.timezone(datetime.timedelta(hours=9))
    if started is not None and started.tzinfo is None:  # naive 면 UTC 로 간주 — aware 와의 뺄셈 TypeError 방지
        started = started.replace(tzinfo=datetime.timezone.utc)
    dur = f"{(finished - started).total_seconds() / 60:.0f}분" if started else "?"
    head = f":white_check_mark: [DATA] {dag_id} 완료 — `{run_id}`, {finished.astimezone(kst):%H:%M} KST, 소요 {dur}"
    return "\n".join([head, *summary]) if summary else head


# ── Airflow 콜백 (context 딕셔너리만 받으므로 airflow import 불필요) ─────────

def notify_failure(context: dict) -> None:
    if isinstance(context.get("exception"), AlreadyAlertedError):
        LOG.info("이미 경보를 보낸 실패 — 실패 알림 생략")
        return
    ti = context.get("task_instance")
    task = context.get("task")
    dag = context.get("dag")
    try:
        text = failure_message(
            dag_id=getattr(dag, "dag_id", "?"),
            task_id=getattr(ti, "task_id", "?"),
            run_id=str(context.get("run_id", "?")),
            try_number=int(getattr(ti, "try_number", 0) or 0),
            max_tries=int(getattr(task, "retries", 0) or 0) + 1,
            when=datetime.datetime.now(datetime.timezone.utc),
            error=str(context.get("exception") or ""),
        )
        post(text)
    except Exception as error:  # noqa: BLE001
        LOG.error("실패 알림 구성 중 오류(무시): %s", error)


def _task_outputs(context: dict) -> dict:
    """건수를 찍는 태스크들의 XCom 마지막 줄을 모은다. 실패해도 알림은 나가야 한다."""
    ti = context.get("task_instance")
    if ti is None or not hasattr(ti, "xcom_pull"):
        return {}
    outputs = {}
    for task_id in COUNT_TASKS:
        try:
            outputs[task_id] = ti.xcom_pull(task_ids=task_id)
        except Exception as error:  # noqa: BLE001
            LOG.warning("%s XCom 조회 실패(무시): %s", task_id, error)
    return outputs


def notify_success(context: dict) -> None:
    dag_run = context.get("dag_run")
    try:
        post(success_message(
            dag_id=getattr(context.get("dag"), "dag_id", "?"),
            run_id=str(context.get("run_id", "?")),
            started=getattr(dag_run, "start_date", None),
            finished=datetime.datetime.now(datetime.timezone.utc),
            summary=collection_summary(_task_outputs(context)),
        ))
    except Exception as error:  # noqa: BLE001
        LOG.error("완료 알림 구성 중 오류(무시): %s", error)


if __name__ == "__main__":  # 수동 발송 테스트: python alerts.py "메시지"
    import sys

    ok = post(sys.argv[1] if len(sys.argv) > 1 else ":bell: [DATA] 알림 테스트 — Airflow 웹훅 연결 확인")
    print("발송 성공" if ok else "발송 실패 또는 웹훅 미설정")
    sys.exit(0 if ok else 1)
