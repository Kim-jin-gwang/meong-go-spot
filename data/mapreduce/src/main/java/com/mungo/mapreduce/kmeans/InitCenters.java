package com.mungo.mapreduce.kmeans;

import com.mungo.mapreduce.common.Dimensions;
import com.mungo.mapreduce.common.VectorUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * k-means++ 초기 중심 생성 — 무작위 초기화의 "같은 군집에서 중심 2개 → 군집 병합" 문제를 막는다.
 * 첫 중심은 무작위, 이후는 기존 중심과의 거리 제곱에 비례하는 확률로 선택.
 *
 * 드라이버(단일 프로세스)에서 벡터를 메모리에 올려 계산한다. 실벡터 45만×768 은 double 로 2.8GB 라 전량은 클라이언트
 * 힙을 넘기므로 {@code -Dkmeans.init.sample=N} 으로 저수지 표본(균등 무작위 N개)만 올린다 — k-means++ 는 표본에서도
 * 잘 퍼진 중심을 주고, 이후 반복이 전량으로 보정한다. 0(기본)이면 전량. 수백만 규모는 k-means|| 로 교체 (README 부채).
 *
 * 사용법: hadoop jar mungo-mapreduce.jar com.mungo.mapreduce.kmeans.InitCenters \
 *   [-Dmungo.vector.meta=/embeddings/{model}/{ver}/_meta.json] [-Dkmeans.init.sample=100000] \
 *   <입력 벡터 경로(글롭 가능)> <k> <출력 중심 파일> [seed]
 *
 * 차원: 설정(_meta.json)이 있으면 모든 벡터를 그 차원으로 검증, 없으면 첫 벡터 차원으로 잠근다 (계약 §4 ③).
 */
public class InitCenters extends Configured implements Tool {

    public static final String SAMPLE_KEY = "kmeans.init.sample";

    @Override
    public int run(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("사용법: InitCenters <input> <k> <output-centers> [seed]");
            return 2;
        }
        Path input = new Path(args[0]);
        int k = Integer.parseInt(args[1]);
        Path output = new Path(args[2]);
        Random random = args.length > 3 ? new Random(Long.parseLong(args[3])) : new Random();

        int dim = Dimensions.configure(getConf());
        int sampleSize = getConf().getInt(SAMPLE_KEY, 0);
        long startMillis = System.currentTimeMillis();
        long[] seen = new long[1];
        List<double[]> vectors = readVectors(getConf(), input, dim, sampleSize, random, seen);
        System.out.printf("벡터 %,d개 읽음 (표본 %,d개, 차원 %d) · %d초%n", seen[0], vectors.size(),
                vectors.isEmpty() ? 0 : vectors.get(0).length, (System.currentTimeMillis() - startMillis) / 1000);
        if (vectors.size() < k) {
            System.err.println("벡터 수(" + vectors.size() + ")가 k(" + k + ")보다 적습니다");
            return 1;
        }

        List<double[]> centers = new ArrayList<>(k);
        centers.add(vectors.get(random.nextInt(vectors.size())));
        double[] minDistance = new double[vectors.size()];
        java.util.Arrays.fill(minDistance, Double.MAX_VALUE);

        while (centers.size() < k) {
            double[] latest = centers.get(centers.size() - 1);
            double total = 0;
            for (int i = 0; i < vectors.size(); i++) {
                double d = VectorUtil.squaredDistance(vectors.get(i), latest);
                if (d < minDistance[i]) {
                    minDistance[i] = d;
                }
                total += minDistance[i];
            }
            if (total <= 0) { // 남은 점이 전부 기존 중심과 동일(중복 데이터) — 무작위 선택으로 폴백
                centers.add(vectors.get(random.nextInt(vectors.size())));
                continue;
            }
            double target = random.nextDouble() * total;
            double cumulative = 0;
            int chosen = vectors.size() - 1;
            for (int i = 0; i < vectors.size(); i++) {
                cumulative += minDistance[i];
                if (cumulative >= target) {
                    chosen = i;
                    break;
                }
            }
            centers.add(vectors.get(chosen));
        }

        FileSystem fs = output.getFileSystem(getConf());
        try (Writer writer = new OutputStreamWriter(fs.create(output, true), StandardCharsets.UTF_8)) {
            for (int i = 0; i < centers.size(); i++) {
                writer.write(i + "\t" + VectorUtil.format(centers.get(i)) + "\n");
            }
        }
        System.out.printf("k-means++ 초기 중심 %d개 → %s · 총 %d초%n", k, output, (System.currentTimeMillis() - startMillis) / 1000);
        return 0;
    }

    /**
     * 입력(파일·디렉터리·글롭)의 벡터를 읽는다. sampleSize > 0 이면 저수지 표본 — 전량을 메모리에 올리지 않는다.
     * seen[0] 에 읽은 전체 줄 수를 돌려준다.
     */
    static List<double[]> readVectors(Configuration conf, Path input, int expectedDim, int sampleSize,
                                      Random random, long[] seen) throws Exception {
        FileSystem fs = input.getFileSystem(conf);
        List<double[]> vectors = new ArrayList<>();
        int dim = expectedDim;
        // 잡 출력 디렉터리·글롭을 입력으로 받는 경우 대비 — _SUCCESS·숨김 파일·하위 디렉터리 제외
        FileStatus[] matched = fs.globStatus(input, p -> !p.getName().startsWith("_") && !p.getName().startsWith("."));
        if (matched == null || matched.length == 0) {
            throw new java.io.FileNotFoundException("입력이 없습니다: " + input);
        }
        List<FileStatus> files = new ArrayList<>();
        for (FileStatus status : matched) {
            if (status.isDirectory()) {
                for (FileStatus child : fs.listStatus(status.getPath(), p -> !p.getName().startsWith("_") && !p.getName().startsWith("."))) {
                    if (child.isDirectory()) {
                        // 파티션 루트(…/shelter-backfill)를 그대로 받으면 하위 yyyymm=… 이 조용히 무시돼 "벡터 0개" 가 된다.
                        // 파티션 안에는 detections-*.jsonl 도 섞여 있어 재귀로 전부 읽어도 틀리므로, 글롭을 요구한다.
                        throw new java.io.IOException("입력 " + status.getPath() + " 에 하위 디렉터리 " + child.getPath().getName()
                                + " 가 있습니다 — 파티션 루트 대신 글롭을 주세요: " + status.getPath() + "/*/vectors-*.tsv");
                    }
                    files.add(child);
                }
            } else {
                files.add(status);
            }
        }
        for (FileStatus status : files) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(fs.open(status.getPath()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) {
                        continue;
                    }
                    double[] vector = VectorUtil.parse(line.substring(tab + 1), dim);
                    if (dim == 0) {
                        dim = vector.length; // 설정이 없으면 첫 벡터 차원으로 잠근다
                    }
                    seen[0]++;
                    if (sampleSize <= 0 || vectors.size() < sampleSize) {
                        vectors.add(vector);
                    } else { // 저수지 표본: i번째 줄을 sampleSize/i 확률로 채택
                        long slot = (long) (random.nextDouble() * seen[0]);
                        if (slot < sampleSize) {
                            vectors.set((int) slot, vector);
                        }
                    }
                }
            }
        }
        return vectors;
    }

    public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new Configuration(), new InitCenters(), args));
    }
}
