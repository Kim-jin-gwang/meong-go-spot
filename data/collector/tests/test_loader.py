import loader


class FakeConsumer:
    def __init__(self) -> None:
        self.subscriptions: list[list[str]] = []

    def subscribe(self, topics: list[str]) -> None:
        self.subscriptions.append(topics)

    def poll(self, _: float) -> None:
        return None

    def assignment(self) -> list[str]:
        return ["shelter.raw[0]"]


def test_consume_all_subscribes_to_public_shelter_topic(monkeypatch) -> None:
    monkeypatch.setattr(loader, "IDLE_POLLS_TO_STOP", 1)
    consumer = FakeConsumer()

    assert loader.consume_all(consumer) == []
    assert consumer.subscriptions == [[loader.TOPIC]]
