#!/usr/bin/env bash
# 컨테이너(backend·Jenkins·runner) → 서버 2(bd-master) 전달 트래픽 차단 — 무인증 HDFS·Kafka 보호.
# 호스트 자신의 통신(Hadoop 워커 등)은 DOCKER-USER 를 거치지 않으므로 영향 없음. 멱등: 이미 있으면 추가하지 않는다.
set -e
iptables -N DOCKER-USER 2>/dev/null || true
iptables -C DOCKER-USER -d 172.26.9.186 -j DROP 2>/dev/null || iptables -I DOCKER-USER 1 -d 172.26.9.186 -j DROP

# 예외 — 백엔드 컨테이너의 사용자 사진 WebHDFS 접근만 통과시킨다 (2026-09-11).
#
# WebHdfsPhotoStorage 는 HTTP WebHDFS 두 단계다. 9870(NameNode HTTP)에 쓰기를 요청하면
# 307 리다이렉트로 DataNode 주소를 받고, 그 DataNode 의 9864(DataNode HTTP)로 실제 바이트를
# 보낸다. 그래서 두 포트가 모두 필요하다. 네이티브 자바 클라이언트의 RPC 포트
# (9000·9866·9867)는 백엔드가 쓰지 않으므로 열지 않는다.
#
# 출발지를 compose 네트워크 서브넷으로 한정한다. compose.prod.yml 의
# networks.default.ipam.config.subnet 과 같아야 한다 — 서브넷이 바뀌면 예외가 빗나가
# 사진 기능이 전량 실패한다.
#
# -I … 1 은 맨 앞 삽입이므로, 이 줄이 위의 DROP 줄보다 **뒤에** 있어야 ACCEPT 가 DROP 위에
# 놓인다. -C 로 존재를 확인하지 않으면 재실행마다 중복 누적된다.
iptables -C DOCKER-USER -s 172.18.0.0/16 -d 172.26.9.186 -p tcp -m multiport --dports 9870,9864 -j ACCEPT 2>/dev/null \
  || iptables -I DOCKER-USER 1 -s 172.18.0.0/16 -d 172.26.9.186 -p tcp -m multiport --dports 9870,9864 -j ACCEPT
