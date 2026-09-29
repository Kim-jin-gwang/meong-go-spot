package com.mungo.mapreduce.knn;

import com.mungo.mapreduce.common.Dimensions;
import com.mungo.mapreduce.kmeans.KMeansDriver;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * kNN 조인 — 신고 질의 × 보호 동물을 K-Means 방 안에서만 비교해 Top-K를 낸다 (2호).
 *
 * 사용법: hadoop jar mungo-mapreduce.jar com.mungo.mapreduce.knn.KnnJoinDriver \
 *   [-Dmungo.vector.meta=/embeddings/{model}/{ver}/_meta.json] \
 *   <질의 벡터> <보호 벡터> <K-Means 중심(수렴본)> <출력> [topK=20]
 *
 * 출력: qid \t rank \t sid \t distance
 * 차원은 _meta.json(또는 -Dmungo.vector.dim)에서 읽어 중심·입력 벡터 모두 검증한다 (계약 §4 ③).
 */
public class KnnJoinDriver extends Configured implements Tool {

    @Override
    public int run(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("사용법: KnnJoinDriver <queries> <shelters> <centers> <output> [topK]");
            return 2;
        }
        Configuration conf = getConf();
        Dimensions.configure(conf);
        conf.set(KMeansDriver.CENTERS_KEY, args[2]);
        conf.setInt(KnnReducer.TOP_K_KEY, args.length > 4 ? Integer.parseInt(args[4]) : 20);

        Job job = Job.getInstance(conf, "knn-join");
        job.setJarByClass(KnnJoinDriver.class);
        MultipleInputs.addInputPath(job, new Path(args[0]), TextInputFormat.class,
                TaggedVectorMapper.QueryMapper.class);
        MultipleInputs.addInputPath(job, new Path(args[1]), TextInputFormat.class,
                TaggedVectorMapper.ShelterMapper.class);
        job.setReducerClass(KnnReducer.class);
        job.setMapOutputKeyClass(IntWritable.class);
        job.setMapOutputValueClass(Text.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);
        FileOutputFormat.setOutputPath(job, new Path(args[3]));
        return job.waitForCompletion(true) ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new Configuration(), new KnnJoinDriver(), args));
    }
}
