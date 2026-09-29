package com.mungo.mapreduce.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/** HDFS의 중심 파일(`clusterId\tv1,v2,...`)을 읽는다 — Mapper setup과 Driver 수렴 판정이 공용. */
public final class Centers {

    private Centers() {}

    public static TreeMap<Integer, double[]> load(Configuration conf, Path path) throws IOException {
        FileSystem fs = path.getFileSystem(conf);
        TreeMap<Integer, double[]> centers = new TreeMap<>();
        FileStatus[] files = fs.isDirectory(path)
                ? fs.listStatus(path, p -> p.getName().startsWith("part-") || p.getName().endsWith(".tsv"))
                : new FileStatus[] {fs.getFileStatus(path)};
        for (FileStatus file : files) {
            if (file.isDirectory()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(fs.open(file.getPath()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] kv = line.split("\t", 2);
                    if (kv.length < 2) {
                        continue; // 파손 라인은 건너뛴다 — setup 전체를 죽이지 않는다
                    }
                    centers.put(Integer.parseInt(kv[0].trim()), VectorUtil.parse(kv[1]));
                }
            }
        }
        if (centers.isEmpty()) {
            throw new IOException("중심 파일이 비어 있습니다: " + path);
        }
        return centers;
    }
}
