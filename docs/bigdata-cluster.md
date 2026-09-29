# 빅데이터 클러스터 운영 가이드

> 상태: **운영 중** — 2026-08-31 구축, 2노드 검증 완료 (복제 2.0, MapReduce 완주, Kafka 왕복).
> 설계 근거는 [ADR-002 D1·D2](adr/ADR-002-제품-스택-결정기록.md), 전체 그림은 [architecture.md](architecture.md).

## 토폴로지

| 호스트 | 별칭 (/etc/hosts) | 역할 |
|---|---|---|
| 서버 2 (`<server2-host>`) | `bd-master` | NameNode · ResourceManager · Kafka(KRaft) + DataNode · NodeManager |
| 서버 1 (`<server1-host>`) | `bd-worker1` | DataNode · NodeManager (서비스 스택과 공존) |

내부 IP는 문서에 기록하지 않는다 — 각 서버에서 `hostname -I`로 확인하고, 두 서버의 `/etc/hosts`에 별칭↔내부 IP 매핑이 이미 등록돼 있다.

설치 경로·버전: Hadoop **3.5.0** `/opt/hadoop`, Kafka **4.2.1(KRaft)** `/opt/kafka`, **Java 17**(둘 다 — Hadoop 3.5는 Java 17 컴파일이라 11에선 `UnsupportedClassVersionError`). 데이터: `/data/hadoop/{nn,dn,tmp}`, `/data/kafka`.

## 접속 주소 (클러스터 내부)

| 서비스 | 주소 |
|---|---|
| HDFS | `hdfs://bd-master:9000` |
| YARN RM | `bd-master:8032` (제출), 웹 UI `bd-master:8088` |
| NameNode 웹 UI | `bd-master:9870` |
| Kafka | `bd-master:9092` (advertised listener가 `bd-master`이므로 클라이언트도 이 이름으로 접속 — /etc/hosts 필요) |

## 웹 UI 접속 주소 (사람이 브라우저로 여는 것)

위 "접속 주소"는 프로그램이 쓰는 주소다. 아래는 **사람이 브라우저로 여는 화면** 모음이다.
규칙의 근거는 아래 "보안 모델" 절에 있다. 2026-09-14 전수 실측.

```
서버 1  <server1-host>   = <server1-public-ip>    (bd-worker1)
서버 2  <server2-host>  = <server2-public-ip>   (bd-master)
```

### 바로 열리는 것

| 주소 | 무엇 | 접근 제한 |
|---|---|---|
| `https://api.meonggo.shop` | 백엔드 API (nginx 443) | 전체 공개 |
| `http://<server1-host>:9090` | Jenkins | 교내 `<교내 대역>` + GitLab 웹훅 `<gitlab-webhook-ip>` |

교외에서 Jenkins 를 보려면 터널을 쓴다.

```bash
ssh -i ~/.ssh/<server-key>.pem -N -L 9090:localhost:9090 ubuntu@<server1-host>
```

### 서버 2 — 터널이 필요하다 (하둡·Airflow 전부)

서버 2 의 UFW 는 **22 번과 서버 1 만** 허용한다. 웹 UI 가 밖에서 안 열리는 것이 정상이다.

```bash
ssh -i ~/.ssh/<server-key>.pem -N \
  -L 9870:localhost:9870 \
  -L 8088:bd-master:8088 \
  -L 9868:localhost:9868 \
  -L 18042:localhost:8042 \
  -L 18080:localhost:8080 \
  ubuntu@<server2-host>
```

| 터널 후 | 무엇 |
|---|---|
| http://localhost:9870 | **HDFS NameNode** — 용량·블록·파일 브라우저 |
| http://localhost:8088 | **YARN ResourceManager** — MapReduce 작업 현황 |
| http://localhost:9868 | Secondary NameNode |
| http://localhost:18042 | NodeManager (서버 2) |
| http://localhost:18080 | **Airflow** — 계정 `admin` / 비밀번호는 서버의 `~/airflow/standalone_admin_password.txt` |

두 가지를 틀리기 쉽다.

- **8088 은 `localhost` 로 터널하면 안 된다.** ResourceManager 가 `0.0.0.0` 이 아니라 내부 IP 에만
  바인딩돼 있어 `-L 8088:bd-master:8088` 이어야 한다. 목적지 이름은 **원격에서** 풀리므로 서버 2 의
  `/etc/hosts` 별칭이 그대로 쓰인다. NameNode 9870 은 `0.0.0.0` 이라 `localhost` 로 된다.
- **Airflow 를 18080 으로 돌린 이유**는 로컬 백엔드(`npm run dev:be`)가 8080 을 쓰기 때문이다.
  로컬에서 백엔드를 안 띄우면 8080 그대로 써도 된다. NodeManager 도 서버 1·2 가 같은 8042 라
  구분하려고 18042·28042 로 나눴다.

### 서버 1 — 터널이 필요한 것

```bash
ssh -i ~/.ssh/<server-key>.pem -N \
  -L 9864:localhost:9864 \
  -L 28042:localhost:8042 \
  ubuntu@<server1-host>
```

| 터널 후 | 무엇 |
|---|---|
| http://localhost:9864 | DataNode (서버 1) — UFW 는 `172.18.0.0/16`(컨테이너)만 허용한다 |
| http://localhost:28042 | NodeManager (서버 1) |

### 레포 밖 서비스

| 주소 | 무엇 |
|---|---|
| https://github.com/Kim-jin-gwang/meong-go-spot | 소스 저장소 |
| 팀 GitLab·Jira (비공개) | MR·CI·이슈 트래킹 |

### 웹 UI 가 아닌 포트 (참고)

| 포트 | 무엇 |
|---|---|
| `bd-master:9000` | HDFS RPC |
| `bd-master:9092` | Kafka |
| `bd-master:8032` | YARN 작업 제출 |
| 서버 1 `127.0.0.1:5432` | PostgreSQL — 루프백 전용 ([deploy-guide.md](deploy-guide.md)) |
| 서버 1 `127.0.0.1:8080` | 백엔드 컨테이너 — 앞단이 nginx 443 |

## 보안 모델 — 반드시 이해하고 변경할 것

- 클라우드 방화벽이 **tcp/udp 1024~65535를 인터넷 전체에 허용**하므로, 고포트 데몬의 유일한 방패는 UFW다
- UFW 규칙: 각 서버가 **상대 서버 내부 IP만 전체 허용** (`ufw allow from <peer-ip>`). 그 외 고포트는 기본 거부 — 웹 UI(9870·8088)도 외부에서 안 열리는 게 정상이다. 보는 방법은 위 "웹 UI 접속 주소" 절에 모아 두었다
- **Hadoop·Kafka를 네이티브로 설치한 이유**: Docker publish 포트는 UFW를 우회해 즉시 인터넷(및 VPC 이웃 팀)에 노출된다. 네이티브 데몬은 UFW를 존중한다. **클러스터 구성요소를 Docker로 옮기지 말 것** — 옮겨야 한다면 UFW 우회 문제를 먼저 풀어야 한다
- **2026-09-08 보안 조치(3건)**:
  - **Jenkins 9090 접근 제한**: `Anywhere` 허용을 지우고 교내 대역(`<교내 대역>`)과 GitLab 웹훅 소스(`<gitlab-webhook-ip>`)만 허용. 교외에서는 SSH 터널 `ssh -i pem -N -L 9090:localhost:9090 ubuntu@<server1-host>` 후 `http://localhost:9090`. 교내 다른 대역이 발견되면 `ufw allow from <대역> to any port 9090 proto tcp` 추가. 방화벽 로그에 9090을 두드린 외부 스캐너 IP가 여럿 있었다
  - **컨테이너 → 서버 2 차단**: 서버 1의 backend·Jenkins·runner 컨테이너는 서버 2와 통신할 이유가 없는데 UFW 피어 규칙(호스트 NAT)으로 무인증 HDFS·Kafka에 닿을 수 있었다. `docker-user-rules.service`(부팅 시 Docker 뒤에 1회, 멱등; `PartOf=docker.service`·`WantedBy=docker.service`라 `systemctl restart docker` 때도 함께 재실행)가 `iptables -I DOCKER-USER -d 172.26.9.186 -j DROP`을 넣는다. 호스트 자신의 통신(Hadoop 워커·배포 스크립트)은 DOCKER-USER를 거치지 않아 영향 없음. `/etc/ufw/after.rules` 방식은 `ufw reload`마다 중복 누적되어 쓰지 않았다. **주의 — [architecture.md](architecture.md)·[photo-upload-policy.md](photo-upload-policy.md)는 백엔드가 사용자 사진을 HDFS(`/data/user/images/`)에 직접 저장·조회하도록 정의한다.** 그 기능이 붙는 MR에서는 이 차단에 예외를 함께 넣어야 한다(작업 항목): `/usr/local/sbin/docker-user-rules.sh`에 기존 DROP 줄과 같은 멱등 패턴으로 `iptables -C DOCKER-USER -s 172.18.0.0/16 -d 172.26.9.186 -p tcp -m multiport --dports 9870,9864 -j ACCEPT || iptables -I DOCKER-USER 1 -s 172.18.0.0/16 -d 172.26.9.186 -p tcp -m multiport --dports 9870,9864 -j ACCEPT`를 **DROP 줄보다 뒤에** 적는다(`-I … 1`은 맨 앞 삽입이라 나중에 실행되는 줄이 위로 간다 → ACCEPT가 DROP 위에 놓임; `-C`가 없으면 재실행마다 중복 누적). **포트 확정 (2026-09-10 정정)**: 백엔드 사진 저장 구현이 `WebHdfsPhotoStorage`(HTTP WebHDFS, 2026-09-09 병합)라 필요한 포트는 **NameNode HTTP 9870과 DataNode HTTP 9864**다. WebHDFS 쓰기는 두 단계여서 둘 다 필요하다 — 9870에 쓰기를 요청하면 307 리다이렉트로 DataNode 주소를 받고, 그 DataNode의 9864로 실제 바이트를 보낸다. 네이티브 자바 클라이언트의 RPC 포트(9000·9866·9867)는 백엔드가 쓰지 않으므로 **넣지 않는다** (이 문서의 이전 판이 그 포트를 적었던 것은 구현 확인 전의 오류다). **리다이렉트 실측(2026-09-10)**: `curl -i -X PUT "http://bd-master:9870/webhdfs/v1/tmp/x?op=CREATE&user.name=ubuntu"` → `Location: http://bd-master:9864/...` — **IP가 아니라 호스트명을 돌려준다.** 컨테이너가 그 이름을 못 풀면 방화벽과 무관하게 실패하는데, 확인해보니 **이미 풀린다**: 백엔드 컨테이너의 `/etc/hosts`에는 없지만 Docker 내장 리졸버(127.0.0.11)가 호스트의 systemd-resolved(127.0.0.53)로 넘기고 그쪽이 서버 1의 `/etc/hosts`를 읽어 `bd-master → 172.26.9.186`을 답한다(`docker exec meong-go-spot-backend-1 getent hosts bd-master`로 확인). `extra_hosts` 설정은 필요 없다. 리다이렉트 대상 DataNode는 서버 1일 수도 서버 2일 수도 있는데 서버 1이면 INPUT 경로라 규칙이 필요 없고 서버 2면 9864 예외가 필요하다 — **어느 쪽이 뽑힐지 모르니 두 포트를 다 넣는다.** 첫 관문인 9870이 막혀 있으므로 지금 기능을 켜면 간헐이 아니라 전량 실패한다. **현재 상태 (2026-09-11 해결)**: 방화벽 예외 두 개를 넣고 `compose.prod.yml`에 세 환경변수의 기본값을 넣었다. compose 네트워크에서 WebHDFS 2단계 쓰기를 끝까지 확인했다 — CREATE로 `http://bd-worker1:9864` Location을 받고 그쪽에 바이트를 보내 **201**, 27바이트 읽기 일치, DELETE 200. 사진이 필수인 게시물 등록이 이 경로에 걸려 있었다. compose 네트워크 한정 — **전제: `compose.prod.yml`에 `networks.default.ipam.config.subnet: 172.18.0.0/16`을 고정**해야 한다(네트워크 재생성 시 서브넷이 바뀌면 예외가 빗나가 차단됨). 백엔드가 나중에 네이티브 클라이언트로 바꾸면 그때 9000·9866·9867로 교체한다. **서버 1의 로컬 DataNode(호스트 IP 172.26.3.162)도 예외가 필요하다 — 이 문서의 이전 판이 "INPUT 경로라 DOCKER-USER를 거치지 않으므로 별도 예외가 필요 없다"고 적은 것은 오류다.** DOCKER-USER를 거치지 않는 것은 맞지만 UFW의 INPUT 체인이 막는다. 2026-09-11 실측: compose 네트워크에서 `172.26.3.162:9864` 연결이 실패했고 `ufw allow from 172.18.0.0/16 to any port 9864 proto tcp`를 넣은 뒤 성공했다. 그리고 클라이언트가 서버 1이면 NameNode는 보통 로컬 DataNode를 고른다 — 실측 6회 모두 `bd-worker1:9864`로 리다이렉트됐다. 즉 **서버 1 쪽 UFW 예외가 오히려 상시 경로다.** 적용 후 `iptables -L DOCKER-USER -n --line-numbers`로 ACCEPT가 1번, DROP이 그 아래인지 확인. 백엔드 첫 HDFS 쓰기 테스트가 timeout 나면 이 규칙을 먼저 의심할 것
  - **GPU 서버 SSH 키 비활성화**: 벌크 임베딩용으로 서버 2 `authorized_keys`에 넣었던 `gpu-embed` 키(공용 GPU 서버 소유)를 `#` 주석으로 비활성. 재임베딩 때 주석을 풀고 `from="70.12.130.107",no-agent-forwarding,no-X11-forwarding`을 붙여 활성, 끝나면 다시 비활성
  - 남은 인프라 항목: TLS 종단(80·9090 평문 — Caddy 443, 팀 결정 대기), fail2ban(서버 1), 서버 간 피어 규칙 포트 축소(MapReduce가 임의 포트를 써 보류)

## 기동·정지

둘 다 systemd로 등록돼 재부팅에도 살아난다 (서버 2에서):

```bash
sudo systemctl status hadoop-cluster   # start-dfs+start-yarn (ssh로 워커까지 기동) — 서버 2
sudo systemctl status kafka            # 서버 2
sudo systemctl status hadoop-worker    # DataNode+NodeManager — 서버 1 (2026-09-08 추가)
```

**서버 1에도 `hadoop-worker` 유닛이 있는 이유(2026-09-08 장애)**: 원래 워커는 서버 2의 `hadoop-cluster`가 SSH로 함께
띄우는 구조여서 서버 1에는 유닛이 없었다. 2026-09-07 12:57 KST 서버 1만 전원이 꺼졌다 켜지자(`systemd-poweroff` 기록, 사용자
reboot 명령 없음 — EC2 stop/start로 추정) 컨테이너는 되살아났지만 워커 데몬은 아무도 올리지 않아 **하루 넘게 단일 노드로 운영**됐다
(라이브 DataNode 1, 복제 부족 블록 1,057). 워커 유닛은 `User=ubuntu`, `Environment=JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`, `Environment=HADOOP_HOME=/opt/hadoop`를 명시하고(`root`로 뜨면 로그·데이터 소유권이 바뀌어 마스터의 SSH 기동이 깨진다), `hdfs --daemon start datanode` + `yarn --daemon start nodemanager`를 `-` 접두로
실행해 마스터가 이미 띄운 경우에도 실패로 표시되지 않는다. 노드 수 확인은 `yarn node -list`·`hdfs dfsadmin -report | grep Live`.

**서버 2 `hadoop-cluster`도 2026-09-09까지는 재부팅 자동 기동이 아니었다**: 유닛에 `[Install]` 절이 없어 `is-enabled`가 `static`이었고,
9/7 장애 뒤 `start-dfs.sh`로 손수 올린 탓에 `inactive`였다 — 이 문서의 "재부팅에도 살아난다"는 그동안 사실이 아니었다(즉시 경보 작업 중 발견).
조치: `[Install] WantedBy=multi-user.target` 추가 후 `enable`, `ExecStart`에 `-` 접두(데몬이 이미 떠 있으면 start 스크립트가 exit 1을 내므로,
접두 없이는 `systemctl start`가 "failed"로 남는다), `systemctl start`로 `active` 전환. 백업 `~/hadoop-cluster.service.bak-2026-09-09`.
확인: `systemctl is-enabled hadoop-cluster` → `enabled`, `is-active` → `active`. **유닛을 손으로 고친 뒤에는 이 두 값을 꼭 본다.**

수동 조작이 필요하면 (ubuntu 계정, JAVA_HOME 등은 .bashrc에 설정됨):

```bash
start-dfs.sh && start-yarn.sh    # 기동
stop-yarn.sh && stop-dfs.sh      # 정지
```

## 상태 확인

```bash
hdfs dfsadmin -report | grep 'Live datanodes'   # → Live datanodes (2)
yarn node -list                                  # → Total Nodes:2
/opt/kafka/bin/kafka-topics.sh --bootstrap-server bd-master:9092 --list
```

## 리소스 배분 (16GB × 2)

| 노드 | YARN NodeManager 상한 | 이유 |
|---|---|---|
| bd-master | 6144 MB | Kafka·NameNode·RM과 공존 |
| bd-worker1 | 4096 MB | backend·postgres·Jenkins·runner와 공존 |

MapReduce 잡이 메모리 부족으로 죽으면 이 값(각 서버 `yarn-site.xml`)부터 본다. 반대로 서비스가 느려지면 YARN이 먹는 양을 줄인다.

## 구축 검증 이력 (2026-08-31)

| 항목 | 결과 |
|---|---|
| HDFS 복제 계수 2 | ✅ fsck Average block replication 2.0 |
| MapReduce 분산 실행 | ✅ pi 예제 21초 완주 (YARN 2노드) |
| Kafka produce→consume 왕복 | ✅ `smoke-test` 토픽 |
| 서버 1 → Kafka(9092)·HDFS(9000) 접속 | ✅ (UFW 피어 규칙 통과) |
| systemd 등록 (재부팅 생존) | ✅ `hadoop-cluster`, `kafka`, `cluster-watch.timer` (서버 2), `hadoop-worker`, `docker-user-rules` (서버 1, 2026-09-08) |

## 알림·상태 점검 (Airflow — 2026-09-08 추가)

2026-09-06 수집 DAG 실패(공공 API TLS 장애)와 2026-09-07 워커 하루 다운이 모두 **알림이 없어 하루 뒤 발견**된 뒤 추가했다.

| 항목 | 동작 |
|---|---|
| `collector_daily` 실패 알림 | 재시도가 소진돼 태스크가 최종 실패하면 `on_failure_callback` → Mattermost `[DATA] … 실패` (DAG·태스크·시도·오류 마지막 줄·로그 경로) |
| `collector_daily` 완료 알림 | 마지막 태스크 성공 시 한 줄 `[DATA] collector_daily 완료 — 소요 N분` |
| `cluster_health` DAG (**월~금 09:00 KST**) | `hdfs dfsadmin -report`·`yarn node -list`·디스크로 라이브 DataNode<2, YARN RUNNING<2, HDFS>80%, 디스크>85% 판정. 매 실행 상태 한 줄(정상/경보). 위반 시 태스크도 실패로 남겨 UI에서 보이게 |
| **즉시 경보** `cluster-watch.timer` (**5분마다, 24시간** — 2026-09-09 추가) | 같은 판정 + `airflow`·`kafka` 서비스 `is-active`. **새 위반이 생기면 즉시** 경보 **한 번**(같은 문제가 이어져도 반복 없음 — 사용자 결정 2026-09-09; 문제 구성이 바뀌면 다시 경보), 풀리면 `클러스터 복구` 한 줄. 정상이면 침묵. 상태는 `~/airflow/cluster-watch-state.json`(직전 문제·첫 감지·마지막 발송) |

- **즉시 경보가 Airflow DAG가 아닌 systemd 타이머인 이유**: Airflow는 `SequentialExecutor`라 22:30 파이프라인(임베딩 포함 최대 2시간)이 도는 동안 다른 태스크가 대기하고, Airflow 자체가 죽으면 Airflow 기반 경보는 아무 말도 못 한다. 타이머는 `python3`(표준 라이브러리만)로 venv·Airflow 없이 돈다. 웹훅은 Airflow와 같은 `airflow-local.env`를 `EnvironmentFile`로 읽는다
- 코드: `data/airflow/dags/alerts.py`(발송, 표준 라이브러리), `cluster_check.py`(파싱·판정·`--watch` 감시 모드 — airflow 없이 import, CI 테스트), `cluster_health.py`(DAG), `data/airflow/systemd/cluster-watch.{service,timer}`. 배포는 dags 네 파일을 `~/airflow/dags/`에 scp, 유닛은 레포 `data/airflow/systemd/`의 두 파일을 서버 2 홈(`~/`)에 scp 한 뒤

  ```bash
  sudo cp ~/cluster-watch.service ~/cluster-watch.timer /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now cluster-watch.timer
  systemctl list-timers cluster-watch.timer        # 다음 실행 시각
  journalctl -u cluster-watch.service -n 20        # 최근 점검 출력 ("정상 — 발송 없음" / 경보 본문)
  ```
- 감시 1회 수동 실행(상태 파일 갱신 포함): `set -a; . ~/airflow/airflow-local.env; set +a; python3 ~/airflow/dags/cluster_check.py --watch`
- **웹훅 설정(1회, 값은 채팅·레포에 적지 않는다)**: Mattermost 에서 **데이터 파이프라인 전용 채널**에 인커밍 웹훅을 만들어(통합 → Incoming Webhooks → 추가, 채널 선택) 그 URL 을 쓴다. Jenkins 배포 채널과 분리하는 것을 권장. 서버 2에서 아래처럼 입력하면 화면에 값이 남지 않는다

  ```bash
  read -rsp 'Mattermost webhook URL: ' MM && echo && printf 'MATTERMOST_WEBHOOK_URL=%s
' "$MM" >> ~/airflow/airflow-local.env && unset MM && chmod 600 ~/airflow/airflow-local.env && sudo systemctl restart airflow
  ```

  확인: `set -a; . ~/airflow/airflow-local.env; set +a; ~/airflow-venv/bin/python ~/airflow/dags/alerts.py` → Mattermost 에 `[DATA] 알림 테스트` 도착
- 웹훅이 비어 있으면 알림은 로그 한 줄로 대체되고 파이프라인은 그대로 돈다 (알림 부재가 실패로 번지지 않게)
- 수동 점검: `~/airflow-venv/bin/python ~/airflow/dags/cluster_check.py`

## 일일 수집 파이프라인 (Airflow — 2026-09-01 구축)

수집→**분실 스냅샷**→적재→이미지→**임베딩**→**색인 배정**→**서빙 스냅샷** 7단계가 **Airflow DAG `collector_daily`로 매일 22:30 KST 자동 실행**된다. 분실 스냅샷(`snapshot_lost_reports`, 2026-09-15 추가)은 분실동물 API 전량을 연락처·상세주소를 지운 뒤 `/data/lost/raw/dt=오늘/` 에 남긴다 — API 가 최근 1개월치만 주고 과거 조회가 없어 매일 받아 두지 않으면 영영 없는 데이터다(`data/collector/lost_snapshot.py`). 5·6번째 태스크는 2026-09-10 추가 — `assign_new_vectors`가 그날 벡터에 K-Means 무리 번호를 붙여 `index/kmeans-k256/assignments/`에 넣고(안 하면 그날 사진이 색인 기반 검색에서 영원히 빠진다), `build_vector_snapshot`이 전체 벡터를 이진 `snapshot/vectors-float32.npy`로 다시 쓴다(상주 worker 로딩 65초 → 0.4초, 실측 100초·메모리 2.9GB). 둘 다 `data/embedding/index_tools.py`. **첫 자동 실행 실측(2026-09-10 22:30 KST, 20분)**: 수집 11초 · 레코드 13초 · 이미지 66초 · 임베딩 980초 · 배정 22초(937장) · 스냅샷 97초(455,486장, 중복 2,112건 제거). 시간의 8할이 임베딩이다. (4번째 태스크 `embed_daily_images`는 2026-09-08 추가 — `hdfs_embed_partition.sh`로 그날 사진을 서버 2 CPU에서 벡터화해 `/embeddings/dinov2_vitb14/v2/shelter-daily/dt=…/`에 적재, 새 사진이 없는 날은 `ALLOW_EMPTY=1`로 정상 종료)
(updTm 실측상 보호소 입력의 ~98.5%가 21시 전 종료 — 심야 꼬리는 다음날 증분이 흡수,
야간은 향후 매칭 배치의 자리).
수집 태스크는 2026-09-25부터 **기본 창 밖 재조회**를 겸한다 — 공공 API 는 기간 없이 부르면 접수일 최근 31일만 주므로
접수 31일이 지난 동물의 보호중 → 종료 전환이 한 달째 안 잡혔다(운영 DB 863건). `main.py` 가 기본 창 뒤에 접수일 180일
전까지를 월 슬라이스로 다시 받아 같은 증분 판정을 태운다(`--resync-days`, 약 40호출 추가·1분 안팎). 상세는 `data/collector/README.md`.
서비스 D1은 `BACKFILL`을 제외한 `DAILY_INCREMENTAL`과 첫 일일 실행 전 `INITIAL_FULL`만
판정한다. 대상 최신 실행이 `RUNNING`이면 `RUNNING`, 최신 종료 실행이 실패면 `FAILED`, 성공
이력이 없으면 `NEVER_SYNCED`로 표시한다. 그 외 마지막 성공 완료가 현재보다 36시간 이전이면
`DELAYED`, 아니면 `SUCCEEDED`다.
지연이나 실패를 후보 없음으로 해석하지 않고 마지막 정상 데이터를 계속 사용한다.
DAG 원본은 저장소 `data/airflow/dags/collector_daily.py`, 수집기 코드는 `data/collector/` —
수정 시 서버 2에 재배포한다:

```bash
scp data/collector/*.py ubuntu@<server2-host>:~/collector-app/
scp infra/reference/breed-species.csv ubuntu@<server2-host>:~/collector-app/reference/   # 분실 스냅샷의 품종→축종 사전
scp data/airflow/dags/collector_daily.py data/airflow/dags/alerts.py ubuntu@<server2-host>:~/airflow/dags/
```

서버에는 레포 트리가 없다 — collector 파일을 `~/collector-app/` 에 **평평하게** 두고, 기준 데이터는 `~/collector-app/reference/` 에
둔다. DAG 의 경로 인자는 이 서버 배치를 가리키며 레포 경로(`infra/reference/…`)와 다르다. 옮긴 뒤 md5 를 대조한다.

구성 (서버 2, 전부 네이티브 — Docker 금지 원칙 유지):

| 항목 | 값 |
|---|---|
| Airflow | 2.11.2, `~/airflow-venv`, SequentialExecutor + SQLite (`~/airflow`) |
| 서비스 | `systemctl status airflow` (standalone 모드, 재부팅 자동 기동) |
| 웹 UI | **localhost 전용** — 터널 명령과 계정은 위 "웹 UI 접속 주소" 절 |
| 수집기 | `~/collector-app/` (venv `~/collector-venv`), 기준 데이터 `~/collector-app/reference/breed-species.csv`, 상태 `~/collector-data/state.json`, API 키 `~/collector.env`(600) |
| 매칭 엔진·worker | **서버 2 가 아니라 서버 1** — `~/match-engine/`·`~/matching-worker/`, venv `~/ai/.venv`, DSN `~/.secrets/match/*.env`(600), systemd `match-engine.service`·`matching-worker.service` (2026-09-15, docs/deploy-guide.md "매칭 엔진" 절) |

자주 쓰는 명령 (서버 2에서):

```bash
~/airflow-venv/bin/airflow dags trigger collector_daily     # 수동 실행
~/airflow-venv/bin/airflow dags list-runs -d collector_daily | head -5   # 최근 실행 상태
```

- **실패 시 그냥 재실행하면 안전하다** — 전 단계가 at-least-once + 멱등 설계 (수집기 README의 계약 참조)
- 컨슈머가 그룹에서 축출돼 오프셋이 꼬였을 때(HDFS 적재는 확인됐는데 재소비되는 경우):
  `/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server bd-master:9092 --group <그룹> --reset-offsets --to-latest --topic shelter.raw --execute`
- **부채**: ① MR 체인 단계에서 병렬이 필요해지면 LocalExecutor+PostgreSQL로 승격 ② 서버 2 메모리가 YARN과 경합하면 Airflow를 서버 1로 이전(SSH 오퍼레이터) ③ ~~실패 알림(MM)은 미구현~~ → 2026-09-08 실패·완료 알림, 2026-09-09 즉시 경보 타이머로 해소 ("알림·상태 점검" 참조)

HDFS replication 2는 한 노드 장애에서 가용성을 유지하는 복제이지 백업이 아니다. 사용자 사진의
복구 목표를 두려면 별도 스냅샷 또는 오프클러스터 백업과 실제 복구 시험이 필요하며, PostgreSQL
매일 암호화 백업·30일 보관·월 1회 복구 검증은
[백엔드 보안·운영 정책](backend-security-operations-policy.md)을 따른다.

## AI 모델 아티팩트 (2026-09-03)

개·고양이 탐지·마스킹 전처리용 YOLO26l-seg와 768차원 임베딩 생성용 DINOv2 ViT-B/14 가중치를 적재했다. FastAPI 임베딩 서빙은 별도 이슈에서 구현한다.

> 이력: 최초 서버 1에 오배포됐던 것을 2026-09-03 서버 2로 이전 완료 (서버 1 `/home/ubuntu/ai` 삭제 확인). 아래는 서버 2 기준이며, 이전 후 로딩·pytest·실측을 서버 2에서 재검증했다.

| 항목 | 값 |
|---|---|
| 호스트 | **서버 2** (`<server2-host>`, bd-master) |
| Python 가상환경 | `/home/ubuntu/ai/.venv` (Python 3.12.3, torch 2.14.0) |
| YOLO 모델 경로 | `/home/ubuntu/ai/models/yolo26l-seg/yolo26l-seg.pt` |
| YOLO 파일 크기·검증 | 약 61 MB · **탐지·마스킹·크롭 검증 완료 (2026-09-03, #56)** — 개·고양이 탐지, 원본 해상도 마스크, 3경로(정상·다중·폴백) pytest 통과 |
| YOLO CPU 실측 | 탐지 장당 약 0.74초 · end-to-end(탐지+임베딩) 1장 1.1초 / 10장 14.2초 (4 vCPU) |
| DINOv2 모델 경로 | `/home/ubuntu/ai/models/dinov2-vitb14/dinov2_vitb14_pretrain.pth` |
| DINOv2 파일 크기·검증 | 약 331 MB · **로딩·추론 검증 완료 (2026-09-03, #46)** — torch.hub 아키텍처에 strict 주입, 768차원·L2 norm=1.0·재현성 pytest 통과 |
| DINOv2 CPU 실측 | 모델 로딩 3.5초(프로세스당 1회) · 1장 0.27초 · 10장 배치 2.5초 (4 vCPU) |

임베딩 프로세스 코드는 저장소 `ai/`(`ai/app/embed.py`, 스펙은 `ai/model.yaml`)이며, 수정 시 서버 2에 재배포한다:

```bash
scp -r ai/model.yaml ai/requirements.txt ai/app ai/tests ubuntu@<server2-host>:/home/ubuntu/ai/
```

가중치는 대용량 바이너리이므로 Git에 커밋하지 않는다. 후속 서비스는 위 절대 경로를 설정으로 주입하고, 모델·마스크·크롭 규격 변경을 하나의 `model_version`으로 관리한다(현재 `v2` — YOLO 마스킹·크롭 전처리 포함, `ai/model.yaml`). 대표 이미지(개·다중 고양이·동물 없음)는 서버 2 `/home/ubuntu/ai/samples/`에 있으며 실모델 pytest(`AI_SAMPLE_DIR`로 재지정 가능)가 사용한다.

## 저장 데이터 목록과 복제 정책 (2026-09-14, 이슈 -147)

HDFS 에 무엇이 얼마나 있고, 각각을 잃으면 어떻게 되는지를 한 표로 둔다. 용량·복제 판단은 이 표에서 시작한다.

| 경로 | 논리 크기 | 복제 | 내용 | 잃으면 |
|---|---|---|---|---|
| `/data/shelter/images-backfill/yyyymm=*` | 129.0 GB | **2** (2026-09-14 전까지 1) | 2024-01~2026-09 공고 사진 452,471장, 월별 tar | 재임베딩 불가. 원본은 공고 종료 후 삭제돼(월 3~5% 404) **완전 재수집이 안 된다** |
| `/data/shelter/images/dt=*` | 6.5 GB | 2 | 일일 수집 사진 (2026-08-31~) | 재수집 가능(최근 건) |
| `/data/shelter/backfill/yyyymm=*` | 1.5 GB | 2 | 2008~2026 레코드 1,634,699건 | API 로 재수집 가능(2,856호출) |
| `/data/shelter/raw` | 13 MB | 2 | 일일 레코드 | 재수집 가능 |
| `/data/lost/raw/dt=*` | ~0.1 MB/일 (10년 ≈ 600 MB) | 2 | 분실 신고 일일 전량 스냅샷(연락처·상세주소 제거, 2026-09-15~) | **재수집 불가** — API 가 1개월 창만 준다 |
| `/embeddings/dinov2_vitb14/v2` | 4.6 GB | 2 | 벡터 TSV·탐지 jsonl·색인·스냅샷 | 이미지가 있으면 GPU 7시간으로 재생성 |
| `/scale`, `/synthetic`, `/test` | 3.5 GB | 2 | 실험 입력 | 재생성 가능 |

### images-backfill 을 복제 2 로 올렸다 — 이슈의 전제가 틀렸다

이슈 -147 은 "복제 2 로 올리려면 bd-master 에 129GB 가 더 필요한데 잔여 135GB 라 사실상 불가"
라고 적었다. **실측하니 틀렸다.** 2026-09-14 `dfsadmin -report`:

| 노드 | DFS 사용 | DFS 여유 |
|---|---|---|
| bd-master (서버 2) | 146.4 GB | 135.1 GB |
| bd-worker1 (서버 1) | 16.5 GB | 249.2 GB |

전체 사용 162.9GB 중 복제 2 인 것은 33.9GB(노드당 약 17GB) 이고, **복제 1 인 129GB 는 전부 bd-master 에
있다** (146.4 ≈ 129 + 17). 백필을 서버 2 에서 썼기 때문에 HDFS 가 첫 사본을 쓰는 노드에 둔 것이다.
2 노드 클러스터에서 복제 2 는 "각 노드에 한 사본" 이므로 **두 번째 사본은 전부 bd-worker1 로 간다.**
bd-master 디스크는 늘지 않고, bd-worker1 은 249 → 약 120GB 여유가 된다.

그래서 축소(오래된 파티션 삭제) 없이 전체를 복제 2 로 올렸다:

```bash
hdfs dfs -setrep 2 /data/shelter/images-backfill        # 파일별 계수 변경은 수 초
hdfs dfsadmin -report | grep "Under replicated"           # NameNode 가 사본을 만드는 동안 줄어든다
hdfs fsck /data/shelter/images-backfill | grep -E "Average block replication|Under-replicated"
```

`setrep` 자체는 메타데이터 변경이라 바로 끝나고, 실제 복사는 NameNode 가 뒤에서 한다(129GB, 사설망).
되돌리려면 `-setrep 1` 이다.

**결과 (2026-09-14 06:31 → 07:08 UTC, 37분)**: `fsck` HEALTHY, 블록 1,432 개 평균 복제 2.0, 복제 부족·손상 0.
bd-worker1 DFS 사용 16.5 → 146.4GB(여유 119.2GB), bd-master 변화 없음(여유 135.1GB). 서버 1 로컬
디스크 62% 사용. 복사 속도는 처음 1.6GB/분에서 후반 4GB/분까지 올랐다 — NameNode 가 복제 작업을
점진적으로 늘린다. 사본이 늘어도 데이터 내용은 바뀌지 않으므로 임베딩·평가·색인에 영향이 없다.

**왜 남겨 두는가 (삭제하지 않는 이유):** 이 사진은 ① 모델이 바뀌면 재임베딩할 유일한 갤러리 원본이고
② 평가 정답쌍(popfile2, 2024-07 이후 약 19만 쌍)의 원본이며 ③ K-Means 색인 학습 입력이다. 원본 서버가
공고 종료 뒤 사진을 지우므로(백필 결손 3.0%, 최근 월일수록 4~5%) 지우면 다시 못 받는다. 2024-01~06
15GB 를 지워 얻는 이득이 없다.

### 앞으로의 용량 (2026-09-14 기준)

| 항목 | 값 |
|---|---|
| 클러스터 여유 (복제 2 후, 실측) | bd-master 135.1GB · bd-worker1 119.2GB |
| 월 증가 | 일일 사진 약 4~5GB/월 × 복제 2 ≈ 10GB/월 (2025-04~06 처럼 많은 달은 20GB) |
| 경보 | `cluster_health`·`cluster-watch` 가 HDFS 사용 80% 초과 시 알림 |
| 여유 소진 예상 | 약 1년. 그 전에 일일 사진 보존 기간(예: 종료 공고 사진 정리) 을 정하면 된다 |

서버 1 의 로컬 디스크는 HDFS 외에 postgres·docker 이미지·nginx 이미지 캐시(상한 8GB) 도 쓴다.
`df -h /` 가 80% 를 넘으면 nginx 캐시 상한부터 낮춘다.

### 2016 이전 레코드는 지우지 않는다

"HDFS 용량을 위해 2016 이전 레코드를 지울까" 는 검토했지만 하지 않는다. 레코드 백필 전체가 1.5GB
(복제 2 로 3.1GB) 라 용량에 의미가 없고, 반환율·공고 기간 같은 통계의 시계열이 길수록 근거가 된다.
용량 문제는 사진(129GB)에 있고, 그것은 위와 같이 복제로 처리했다.

## 함정 기록

- **Hadoop 3.5.0 = Java 17 필요** (Java 11이면 class file 61.0 오류)
- Kafka 4.x KRaft 포맷은 `--standalone` 플래그 필요 (`controller.quorum.voters` 미설정 시)
- 마스터→워커 SSH 신뢰(ubuntu 계정 ed25519)가 하둡 기동의 전제 — authorized_keys는 **append만** 할 것 (덮어쓰면 서버 접속 불능 = 초기화행)
- **워커 서버가 단독 재부팅되면 데몬이 죽은 채로 남는다** (2026-09-07 실제 발생, 다음 날 보안 점검 중 발견) — 서버 1 `hadoop-worker` 유닛으로 해결. 클러스터 작업 전 `yarn node -list`로 노드 수를 보는 습관
- 공공 API가 통째로 응답하지 않는 날이 있다 (2026-09-06 22:30 DAG: TLS 핸드셰이크 타임아웃, 25분간 재시도 9회 전부 실패, 다음 날 정상). 수집기는 전량 스냅샷 diff라 다음 날 실행이 변경분을 흡수하지만, 실패를 사람이 알 수 있게 알림(-110)이 필요하다
