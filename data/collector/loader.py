"""`shelter.raw` 토픽을 비울 때까지 읽어 HDFS 일자 경로에 적재하는 배치형 컨슈머.

상주 데몬이 아니다 — 밀린 메시지를 전부 읽고, HDFS 업로드가 성공한 뒤에만
오프셋을 커밋하고 종료한다(at-least-once: 업로드 후·커밋 전 중단 시 중복 적재
가능 — 하류는 desertionNo+updTm로 멱등 처리한다).

사용법:
    python loader.py --bootstrap bd-master:9092 --hdfs-dir /data/shelter/raw
"""

import argparse
import datetime
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from confluent_kafka import Consumer, KafkaException

TOPIC = "shelter.raw"
GROUP_ID = "hdfs-loader"
IDLE_POLLS_TO_STOP = 5  # 1초 poll이 연속 5회 비면 "다 읽었다"로 판단
MAX_WAIT_SEC = 120  # 파티션 배정이 영영 안 되는 장애 시에도 이 시간 안에 반드시 종료


def consume_all(consumer: Consumer, topic: str = TOPIC) -> list[bytes]:
    """topic의 밀린 메시지를 전부 읽는다 — imager 등 다른 배치 컨슈머도 재사용한다."""
    consumer.subscribe([topic])
    messages: list[bytes] = []
    idle = 0
    deadline = time.monotonic() + MAX_WAIT_SEC
    while idle < IDLE_POLLS_TO_STOP:
        if time.monotonic() > deadline:
            if not consumer.assignment():
                raise RuntimeError(f"{MAX_WAIT_SEC}초 내 파티션 배정 실패 — 브로커·토픽 상태를 확인하세요")
            break  # 배정은 됐지만 메시지가 계속 흐르는 경우도 배치 상한에서 끊는다
        msg = consumer.poll(1.0)
        if msg is None:
            if consumer.assignment():  # 파티션 배정 전(그룹 조인 중)의 빈 poll은 idle로 세지 않는다
                idle += 1
            continue
        if msg.error():
            raise KafkaException(msg.error())
        idle = 0
        messages.append(msg.value())
    return messages


def upload_to_hdfs(messages: list[bytes], hdfs_dir: str) -> str:
    """스풀 파일에 모아 HDFS에 -put 한다. 반환값은 목적지 경로."""
    date_partition = datetime.date.today().isoformat()
    dest_dir = f"{hdfs_dir}/dt={date_partition}"
    dest_path = f"{dest_dir}/part-{int(time.time() * 1000)}.jsonl"  # ms 단위 — 동시·연속 실행 충돌 방지

    with tempfile.NamedTemporaryFile("wb", suffix=".jsonl", delete=False) as spool:
        for value in messages:
            spool.write(value)
            spool.write(b"\n")
        spool_path = Path(spool.name)
    try:
        subprocess.run(["hdfs", "dfs", "-mkdir", "-p", dest_dir], check=True)
        subprocess.run(["hdfs", "dfs", "-put", str(spool_path), dest_path], check=True)
    finally:
        spool_path.unlink(missing_ok=True)
    return dest_path


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Kafka → HDFS 적재기 (배치형)")
    parser.add_argument("--bootstrap", required=True, help="Kafka bootstrap servers")
    parser.add_argument("--hdfs-dir", required=True, help="HDFS 적재 루트 (예: /data/shelter/raw)")
    args = parser.parse_args()

    consumer = Consumer(
        {
            "bootstrap.servers": args.bootstrap,
            "group.id": GROUP_ID,
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,  # 업로드 성공 후에만 수동 커밋
        }
    )
    try:
        messages = consume_all(consumer)
        if not messages:
            print("새 메시지 없음 — 적재 생략")
            return 0
        dest_path = upload_to_hdfs(messages, args.hdfs_dir)
        consumer.commit(asynchronous=False)  # 업로드 후 동기 커밋 — 종료 전에 확정을 보장
        print(f"적재 완료: {len(messages)}건 → {dest_path}")
        return 0
    finally:
        consumer.close()


if __name__ == "__main__":
    sys.exit(main())
