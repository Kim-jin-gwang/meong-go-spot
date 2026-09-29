package com.mungo.mapreduce.kmeans;

import com.mungo.mapreduce.common.Centers;
import com.mungo.mapreduce.common.Dimensions;
import com.mungo.mapreduce.common.VectorUtil;

import java.util.Map;
import java.util.TreeMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.CombineTextInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * K-Means 반복 드라이버.
 *
 * 사용법: hadoop jar mungo-mapreduce.jar com.mungo.mapreduce.kmeans.KMeansDriver \
 *   [-Dmungo.vector.meta=/embeddings/{model}/{ver}/_meta.json] [-Dkmeans.split.maxsize=134217728] \
 *   <입력 벡터 경로(글롭 가능)> <초기 중심 파일> <작업 디렉터리> [최대반복=20] [수렴임계 L2=1e-4]
 *
 * 반복마다 중심 잡을 돌리고, 중심 이동량(L2 제곱합)이 임계 미만이면 종료.
 * 마지막에 맵 전용 배정 잡으로 <작업 디렉터리>/assignments 에 `id\tclusterId`를 쓴다.
 *
 * 차원은 _meta.json(또는 -Dmungo.vector.dim)에서 읽어 conf 에 심고, 매퍼가 중심·입력 벡터를 그 차원으로 검증한다
 * (계약 §4 ③ — 상수 없음). 입력은 CombineTextInputFormat 으로 묶는다: 실벡터는 월별 tar 단위의 작은 TSV 수백 개라
 * 파일당 맵 태스크 1개면 기동 오버헤드가 계산 시간을 넘는다. 기본 128MB 묶음, -Dkmeans.split.maxsize 로 조정.
 */
public class KMeansDriver extends Configured implements Tool {

    public static final String CENTERS_KEY = "kmeans.centers.path";
    public static final String SPLIT_MAX_KEY = "kmeans.split.maxsize";
    private static final long DEFAULT_SPLIT_MAX = 128L * 1024 * 1024;

    @Override
    public int run(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("사용법: KMeansDriver <input> <initial-centers> <work-dir> [maxIter] [epsilon]");
            return 2;
        }
        Path input = new Path(args[0]);
        Path centers = new Path(args[1]);
        Path workDir = new Path(args[2]);
        int maxIterations = args.length > 3 ? Integer.parseInt(args[3]) : 20;
        double epsilon = args.length > 4 ? Double.parseDouble(args[4]) : 1e-4;

        Configuration conf = getConf();
        Dimensions.configure(conf);
        FileSystem fs = workDir.getFileSystem(conf);

        long startMillis = System.currentTimeMillis();
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            Path next = new Path(workDir, "centers-" + iteration);
            fs.delete(next, true);

            Job job = Job.getInstance(conf, "kmeans-iter-" + iteration);
            job.setJarByClass(KMeansDriver.class);
            job.getConfiguration().set(CENTERS_KEY, centers.toString());
            configureInput(job, input);
            job.setMapperClass(KMeansMapper.class);
            // 벤치마크용 스위치: -Dkmeans.combiner=false 로 끄면 Combiner의 셔플 절감 효과를 실측할 수 있다
            if (conf.getBoolean("kmeans.combiner", true)) {
                job.setCombinerClass(KMeansCombiner.class);
            }
            job.setReducerClass(KMeansReducer.class);
            job.setMapOutputKeyClass(IntWritable.class);
            job.setMapOutputValueClass(Text.class);
            job.setOutputKeyClass(IntWritable.class);
            job.setOutputValueClass(Text.class);
            FileOutputFormat.setOutputPath(job, next);
            long iterStart = System.currentTimeMillis();
            if (!job.waitForCompletion(true)) {
                return 1;
            }

            double shift = totalShift(conf, centers, next);
            System.out.printf("반복 %d — 중심 이동량(L2^2 합): %.6f · %d초%n", iteration, shift,
                    (System.currentTimeMillis() - iterStart) / 1000);
            centers = next;
            if (shift < epsilon) {
                System.out.println("수렴 — 반복 종료");
                break;
            }
        }

        Path assignments = new Path(workDir, "assignments");
        fs.delete(assignments, true);
        Job assign = Job.getInstance(conf, "kmeans-assign");
        assign.setJarByClass(KMeansDriver.class);
        assign.getConfiguration().set(CENTERS_KEY, centers.toString());
        configureInput(assign, input);
        assign.setMapperClass(AssignMapper.class);
        assign.setNumReduceTasks(0);
        assign.setOutputKeyClass(Text.class);
        assign.setOutputValueClass(Text.class);
        FileOutputFormat.setOutputPath(assign, assignments);
        if (!assign.waitForCompletion(true)) {
            return 1;
        }
        System.out.printf("최종 중심: %s | 배정: %s | 총 %d초%n", centers, assignments,
                (System.currentTimeMillis() - startMillis) / 1000);
        return 0;
    }

    /** 입력 글롭 + 작은 파일 묶기 — 261개 월별 TSV 가 맵 261개가 되지 않게. */
    private void configureInput(Job job, Path input) throws java.io.IOException {
        job.setInputFormatClass(CombineTextInputFormat.class);
        CombineTextInputFormat.setMaxInputSplitSize(job, job.getConfiguration().getLong(SPLIT_MAX_KEY, DEFAULT_SPLIT_MAX));
        FileInputFormat.addInputPath(job, input);
    }

    /** 이전/새 중심 간 L2 제곱합 — 새로 비거나 생긴 클러스터는 이동량 무한대로 취급하지 않고 건너뛴다. */
    private double totalShift(Configuration conf, Path previous, Path next) throws Exception {
        TreeMap<Integer, double[]> before = Centers.load(conf, previous);
        TreeMap<Integer, double[]> after = Centers.load(conf, next);
        double sum = 0;
        for (Map.Entry<Integer, double[]> entry : after.entrySet()) {
            double[] old = before.get(entry.getKey());
            if (old != null) {
                sum += VectorUtil.squaredDistance(old, entry.getValue());
            }
        }
        if (after.size() < before.size()) {
            System.out.printf("주의: 빈 클러스터 %d개 — 중심이 %d → %d개%n", before.size() - after.size(), before.size(), after.size());
        }
        return sum;
    }

    public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new Configuration(), new KMeansDriver(), args));
    }
}
