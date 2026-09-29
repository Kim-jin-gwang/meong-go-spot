"""수집기 테스트 공통 설정 — 모듈이 패키지가 아니라 스크립트 묶음이므로 경로를 직접 넣는다.

테스트는 네트워크·Kafka·HDFS 없이 돈다 (외부 호출은 monkeypatch로 끊음). 그래서 CI 게이트에
넣을 수 있고, 서버 환경이 필요한 검증(실제 API·HDFS)은 data/experiments/ 기록이 담당한다.
"""

import sys
from pathlib import Path

COLLECTOR_DIR = Path(__file__).resolve().parent.parent
if str(COLLECTOR_DIR) not in sys.path:
    sys.path.insert(0, str(COLLECTOR_DIR))
