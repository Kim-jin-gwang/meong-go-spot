package com.meonggo.backend.auth;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** 로컬은 컨테이너, Docker socket이 없는 CI는 전용 Redis sidecar를 사용한다. */
public final class RedisTestServer {
    private static final String URL = start();

    private RedisTestServer() {}

    public static String url() {
        return URL;
    }

    private static String start() {
        String external = System.getenv("TEST_REDIS_URL");
        if (external != null && !external.isBlank()) {
            return external;
        }
        GenericContainer<?> redis =
                new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                        .withExposedPorts(6379)
                        .withCommand("redis-server", "--maxmemory-policy", "noeviction");
        redis.start();
        return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    }
}
