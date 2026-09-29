"""행정구역 변환 테스트 공통 설정 — 모듈이 패키지가 아니라 스크립트라 경로를 직접 넣는다."""

import sys
from pathlib import Path

REFERENCE_DIR = Path(__file__).resolve().parent.parent
if str(REFERENCE_DIR) not in sys.path:
    sys.path.insert(0, str(REFERENCE_DIR))
