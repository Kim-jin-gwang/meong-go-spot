package com.mungo.mapreduce.jaccard;

import java.io.IOException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * 자카드 셀프조인 (5호) — 속성 태그 집합이 비슷한 동물 쌍을 찾는다. 2단계 잡:
 *
 *   Job1 (후보 생성): 태그 → 그 태그를 가진 id들 → 태그를 공유하는 모든 쌍 방출
 *   Job2 (집계): 쌍별 공유 태그 수 c 합산 → J = c / (|A| + |B| - c) ≥ 임계값만 출력
 *
 * 입력: id \t tag1,tag2,...  (tools/make_jaccard_input.py)
 * 사용법: hadoop jar ... jaccard.JaccardDriver <tags> <workdir> [임계=0.6]
 * 출력: <workdir>/pairs — idA \t idB \t jaccard
 */
public class JaccardDriver extends Configured implements Tool {

    static final String THRESHOLD_KEY = "jaccard.threshold";

    /** Job1 매퍼: id의 태그마다 (태그, id|집합크기) 방출. */
    public static class TagMapper extends Mapper<LongWritable, Text, Text, Text> {
        private final Text outKey = new Text();
        private final Text outValue = new Text();

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            String[] cols = value.toString().split("\t", 2);
            if (cols.length < 2) {
                return;
            }
            String[] tags = cols[1].split(",");
            for (String tag : tags) {
                outKey.set(tag);
                outValue.set(cols[0] + "|" + tags.length);
                context.write(outKey, outValue);
            }
        }
    }

    /** Job2 매퍼: Job1 출력(쌍 \t 1)을 그대로 키-값으로 넘긴다. */
    public static class PairMapper extends Mapper<LongWritable, Text, Text, Text> {
        private final Text outKey = new Text();
        private static final Text ONE = new Text("1");

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            String[] cols = value.toString().split("\t", 2);
            outKey.set(cols[0]);
            context.write(outKey, ONE);
        }
    }

    @Override
    public int run(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("사용법: JaccardDriver <tags> <workdir> [threshold]");
            return 2;
        }
        Configuration conf = getConf();
        conf.setDouble(THRESHOLD_KEY, args.length > 2 ? Double.parseDouble(args[2]) : 0.6);

        Path candidates = new Path(args[1], "candidates");
        Path pairs = new Path(args[1], "pairs");
        FileSystem fs = candidates.getFileSystem(conf);
        fs.delete(candidates, true);
        fs.delete(pairs, true);

        Job job1 = Job.getInstance(conf, "jaccard-candidates");
        job1.setJarByClass(JaccardDriver.class);
        job1.setMapperClass(TagMapper.class);
        job1.setReducerClass(CandidatePairReducer.class);
        job1.setMapOutputKeyClass(Text.class);
        job1.setMapOutputValueClass(Text.class);
        job1.setOutputKeyClass(Text.class);
        job1.setOutputValueClass(Text.class);
        FileInputFormat.addInputPath(job1, new Path(args[0]));
        FileOutputFormat.setOutputPath(job1, candidates);
        if (!job1.waitForCompletion(true)) {
            return 1;
        }

        Job job2 = Job.getInstance(conf, "jaccard-score");
        job2.setJarByClass(JaccardDriver.class);
        job2.setMapperClass(PairMapper.class);
        job2.setReducerClass(JaccardScoreReducer.class);
        job2.setMapOutputKeyClass(Text.class);
        job2.setMapOutputValueClass(Text.class);
        job2.setOutputKeyClass(Text.class);
        job2.setOutputValueClass(Text.class);
        FileInputFormat.addInputPath(job2, candidates);
        FileOutputFormat.setOutputPath(job2, pairs);
        return job2.waitForCompletion(true) ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new Configuration(), new JaccardDriver(), args));
    }
}
