"""증분 레코드를 Kafka `shelter.raw` 토픽으로 발행한다.

key=desertionNo — 같은 개체의 갱신이 항상 같은 파티션으로 가 순서가 보장된다.
value=API 원문 JSON — 가공은 하류의 몫 (README 설계 메모 참조).
"""

import json

from confluent_kafka import Producer

TOPIC = "shelter.raw"
FLUSH_TIMEOUT_SEC = 60


def publish(bootstrap: str, records: list[dict]) -> int:
    """레코드 전부를 발행하고 브로커 확인(acks=all)까지 기다린다. 실패가 있으면 예외."""
    if not records:
        return 0
    producer = Producer({"bootstrap.servers": bootstrap, "acks": "all"})
    errors: list[str] = []

    def on_delivery(err, msg) -> None:  # noqa: ANN001 — confluent-kafka 콜백 시그니처
        if err is not None:
            errors.append(str(err))

    for record in records:
        producer.produce(
            TOPIC,
            key=str(record.get("desertionNo") or ""),
            value=json.dumps(record, ensure_ascii=False).encode("utf-8"),
            on_delivery=on_delivery,
        )
        producer.poll(0)  # 콜백 처리 + 내부 큐 비우기 — 대량 발행 시 BufferError 방지
    remaining = producer.flush(FLUSH_TIMEOUT_SEC)
    if remaining > 0:
        raise RuntimeError(f"발행 미완료 {remaining}건 (flush 타임아웃)")
    if errors:
        raise RuntimeError(f"발행 실패 {len(errors)}건 — 첫 오류: {errors[0]}")
    return len(records)
