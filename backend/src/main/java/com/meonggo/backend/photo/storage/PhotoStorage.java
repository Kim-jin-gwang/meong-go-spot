package com.meonggo.backend.photo.storage;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** 내부 서버 생성 경로만 받는 HDFS 경계. 구현체는 장애 원문을 외부로 전달하지 않는다. */
public interface PhotoStorage {
    void write(String path, byte[] bytes);

    void move(String source, String destination);

    InputStream open(String path);

    void delete(String path);

    List<Entry> list(String directory);

    record Entry(String path, boolean directory, Instant modifiedAt) {
        @Override
        public String toString() {
            return "PhotoStorage.Entry[redacted]";
        }
    }
}
