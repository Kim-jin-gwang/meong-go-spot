// 2026-08-31 서버 1 첫 배포 성공으로 초안 상태 해제. 설정 절차와 롤백은 docs/deploy-guide.md 참조.
// 이전 프로젝트 Jenkinsfile 이식 + Test 스테이지 추가 (ADR-001 D6).
// 파이프라인: Checkout → Validate → Test → Build & Deploy → Health check → 알림

def sendMMNotify(boolean success, Map info = [:]) {
    try {
        def titleLine = success
            ? "## :white_check_mark: 서비스 배포 성공"
            : "## :x: 서비스 배포 실패"

        def lines = []

        if (info.mention) {
            lines << "**담당자**: ${info.mention}"
        }

        if (info.branch) {
            lines << "**대상 브랜치**: `${info.branch}`"
        }

        if (info.commit?.msg) {
            lines << "**커밋**: ${info.commit.msg}"
        }

        if (info.buildUrl) {
            lines << "**빌드 상세**: [Jenkins에서 확인](${info.buildUrl})"
        }

        if (!success && info.details) {
            lines << "**실패 정보**: ${info.details}"
        }

        def text = "${titleLine}\n" +
            (lines ? "\n" + lines.join("\n") : "")

        writeFile(
            file: 'mattermost-payload.json',
            text: groovy.json.JsonOutput.toJson([
                text      : text,
                username  : 'Jenkins',
                icon_emoji: ':robot_face:'
            ])
        )

        withCredentials([
            string(
                credentialsId: 'mattermost-webhook',
                variable: 'MM_WEBHOOK'
            )
        ]) {
            def notificationStatus = sh(
                script: '''
                    curl -sS -f \
                      -X POST \
                      -H 'Content-Type: application/json' \
                      --data-binary @mattermost-payload.json \
                      "$MM_WEBHOOK"
                ''',
                returnStatus: true
            )

            if (notificationStatus != 0) {
                echo 'Mattermost notification failed.'
            }
        }
    } catch (Exception error) {
        // 알림 실패 때문에 실제 배포 결과가 실패로 바뀌지 않도록 처리합니다.
        echo "Mattermost notification error: ${error.message}"
    }
}

pipeline {
    agent any

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        timestamps()
    }

    environment {
        COMPOSE_PROJECT = 'meong-go-spot'
        IMAGE_TAG = "${env.BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Validate') {
            steps {
                sh '''
                    test -f backend/Dockerfile.prod
                    test -f compose.prod.yml
                '''
            }
        }

        // 배포 직전 최종 확인 — MR 게이트 대용이 아니다 (게이트는 GitLab CI).
        // 이 파이프라인의 배포 대상은 backend뿐이다 (Android 앱은 스토어/APK 배포).
        // Jenkins 에이전트에 JDK를 요구하지 않도록 컨테이너에서 실행하고,
        // named volume으로 의존성 캐시를 유지한다.
        stage('Test') {
            steps {
                // 백엔드 테스트는 실제 PostgreSQL을 사용하므로 임시 DB를 붙였다 정리한다.
                sh '''
                    docker rm -f jenkins-test-db >/dev/null 2>&1 || true
                    docker rm -f jenkins-test-redis >/dev/null 2>&1 || true
                    docker network rm jenkins-test >/dev/null 2>&1 || true
                    docker network create jenkins-test
                    docker run -d --name jenkins-test-db --network jenkins-test \
                      -e POSTGRES_DB=app -e POSTGRES_USER=app -e POSTGRES_PASSWORD=app-ci-only \
                      postgres:17-alpine

                    docker run -d --name jenkins-test-redis --network jenkins-test \
                      redis:7.4-alpine redis-server --maxmemory-policy noeviction

                    for i in $(seq 1 30); do
                        if docker exec jenkins-test-db pg_isready -U app >/dev/null 2>&1; then
                            echo "postgres ready"
                            break
                        fi
                        if [ "$i" -eq 30 ]; then
                            echo "postgres readiness check timed out"
                            exit 1
                        fi
                        sleep 1
                    done

                    docker run --rm --network jenkins-test \
                      -e SPRING_DATASOURCE_URL=jdbc:postgresql://jenkins-test-db:5432/app \
                      -e SPRING_DATASOURCE_USERNAME=app \
                      -e SPRING_DATASOURCE_PASSWORD=app-ci-only \
                      -e TEST_REDIS_URL=redis://jenkins-test-redis:6379 \
                      -v "$PWD/backend":/app \
                      -v jenkins-gradle-cache:/root/.gradle \
                      -w /app \
                      eclipse-temurin:21-jdk \
                      sh -c "./gradlew check --no-daemon"
                '''
            }
            post {
                always {
                    sh '''
                        docker rm -f jenkins-test-db >/dev/null 2>&1 || true
                        docker rm -f jenkins-test-redis >/dev/null 2>&1 || true
                        docker network rm jenkins-test >/dev/null 2>&1 || true
                    '''
                }
            }
        }

        stage('Build and Deploy') {
            steps {
                withCredentials([
                    file(
                        credentialsId: 'prod-env-file',
                        variable: 'PROD_ENV_FILE'
                    )
                ]) {
                    sh '''
                        docker compose \
                          --env-file "$PROD_ENV_FILE" \
                          -f compose.prod.yml \
                          -p "$COMPOSE_PROJECT" \
                          config >/dev/null

                        docker compose \
                          --env-file "$PROD_ENV_FILE" \
                          -f compose.prod.yml \
                          -p "$COMPOSE_PROJECT" \
                          up -d --build --remove-orphans
                    '''
                }
            }
        }

        stage('Health check') {
            steps {
                // Jenkins는 컨테이너 안에서 돌므로 호스트를 host.docker.internal로 본다
                // (컨테이너 기동 시 --add-host=host-gateway로 주입).
                //
                // 호스트 80이 아니라 nginx의 443을 본다. 배포가 backend를 127.0.0.1:8080으로
                // 옮기면 80에는 아무것도 남지 않으므로 80을 보는 검사는 배포 직후 실패한다.
                // nginx는 upstream을 8080 우선 / 80 backup으로 이중화해 두어 배포 전후 모두
                // 응답한다 (infra/nginx/api.meonggo.shop.conf).
                //
                // --resolve로 인증서 이름과 접속 주소를 분리한다. TLS 검증은 그대로 한다 —
                // 검증을 끄면 종단이 실제로 동작하는지 확인하는 의미가 없어진다.
                sh '''
                    HOST_IP=$(getent hosts host.docker.internal | awk '{ print $1; exit }')
                    if [ -z "$HOST_IP" ]; then
                        echo "host.docker.internal을 해석할 수 없습니다."
                        exit 1
                    fi
                    for i in $(seq 1 20); do
                        if curl -sf --resolve api.meonggo.shop:443:"$HOST_IP" \
                            https://api.meonggo.shop/api/v1/ping >/dev/null; then
                            echo "Health check passed."
                            exit 0
                        fi
                        echo "Waiting for service... ($i/20)"
                        sleep 3
                    done
                    echo "Health check failed."
                    exit 1
                '''
            }
        }
    }

    post {
        success {
            script {
                def commitMessage = sh(
                    script: 'git log -1 --pretty=%s',
                    returnStdout: true
                ).trim()

                sendMMNotify(true, [
                    branch  : env.BRANCH_NAME ?: env.GIT_BRANCH ?: 'unknown',
                    commit  : [
                        msg: commitMessage
                    ],
                    buildUrl: env.BUILD_URL
                ])
            }
        }

        failure {
            script {
                sendMMNotify(false, [
                    branch  : env.BRANCH_NAME ?: env.GIT_BRANCH ?: 'unknown',
                    buildUrl: env.BUILD_URL,
                    details : 'Jenkins 콘솔 로그를 확인하세요.'
                ])
            }
        }

        cleanup {
            sh 'rm -f mattermost-payload.json'
        }
    }
}
