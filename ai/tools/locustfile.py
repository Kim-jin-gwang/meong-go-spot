"""Locust 부하 시나리오 — M2 요청(사진 1/3/10장 혼합)을 bench_serve 에 전송 (#105).

장수 분포는 "대부분 1~3장, 가끔 10장" 가정: 1장 50% / 3장 35% / 10장 15%.
동시 사용자 단계(1→3→5→10)는 실행 스크립트가 -u 를 바꿔가며 headless 로 돌린다.

사용법 (서버 2, 벤치 venv 에서):
    locust -f locustfile.py --headless -u 3 -r 1 -t 3m \
        --host http://127.0.0.1:8100 --csv ~/bench/results/load_u3
"""

from __future__ import annotations

from locust import HttpUser, between, task


class M2AnalyzeUser(HttpUser):
    # 사용자가 결과를 보고 다음 행동까지 쉬는 시간 — 연속 폭주가 아닌 실사용 근사
    wait_time = between(1, 3)

    @task(50)
    def analyze_1(self) -> None:
        self.client.post("/analyze?count=1", name="/analyze c=1")

    @task(35)
    def analyze_3(self) -> None:
        self.client.post("/analyze?count=3", name="/analyze c=3")

    @task(15)
    def analyze_10(self) -> None:
        self.client.post("/analyze?count=10", name="/analyze c=10")
