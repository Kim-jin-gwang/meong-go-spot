package com.mungo.mapreduce.theta;

import java.io.IOException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * 쎄타조인 (4호) — 신고 × 보호 동물을 도메인 조건으로 잇는다:
 * 같은 품종 ∧ 같은 지역(시도) ∧ 실종일 ≤ 발견일 ≤ 실종일+기간.
 *
 * 앞의 등가 조건(품종·지역)으로 버킷을 만들고(맵 키), 비등가 날짜 조건은
 * 리듀서에서 평가한다 — 등가 블로킹 + θ 술어 패턴.
 *
 * 입력 형식: id \t 품종 \t 지역 \t 날짜(YYYYMMDD)  — tools/make_theta_inputs.py 산출
 * 사용법: hadoop jar ... theta.ThetaJoinDriver <queries> <shelters> <output> [기간일=90]
 * 출력: qid \t sid \t 발견일
 */
public class ThetaJoinDriver extends Configured implements Tool {

    static final String WINDOW_DAYS_KEY = "theta.window.days";

    /** 등가 조건(품종|지역)을 키로, "태그|id|날짜"를 값으로 방출한다. */
    public abstract static class MetaMapper extends Mapper<LongWritable, Text, Text, Text> {
        private final Text outKey = new Text();
        private final Text outValue = new Text();

        protected abstract char tag();

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            String[] cols = value.toString().split("\t");
            if (cols.length < 4) {
                return;
            }
            outKey.set(cols[1] + "|" + cols[2]);
            outValue.set(tag() + "|" + cols[0] + "|" + cols[3]);
            context.write(outKey, outValue);
        }
    }

    public static class QueryMapper extends MetaMapper {
        @Override
        protected char tag() {
            return 'Q';
        }
    }

    public static class ShelterMapper extends MetaMapper {
        @Override
        protected char tag() {
            return 'S';
        }
    }

    @Override
    public int run(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("사용법: ThetaJoinDriver <queries> <shelters> <output> [windowDays]");
            return 2;
        }
        Configuration conf = getConf();
        conf.setInt(WINDOW_DAYS_KEY, args.length > 3 ? Integer.parseInt(args[3]) : 90);

        Job job = Job.getInstance(conf, "theta-join");
        job.setJarByClass(ThetaJoinDriver.class);
        MultipleInputs.addInputPath(job, new Path(args[0]), TextInputFormat.class, QueryMapper.class);
        MultipleInputs.addInputPath(job, new Path(args[1]), TextInputFormat.class, ShelterMapper.class);
        job.setReducerClass(ThetaJoinReducer.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);
        FileOutputFormat.setOutputPath(job, new Path(args[2]));
        return job.waitForCompletion(true) ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new Configuration(), new ThetaJoinDriver(), args));
    }
}
