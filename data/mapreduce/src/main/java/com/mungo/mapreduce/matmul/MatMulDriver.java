package com.mungo.mapreduce.matmul;

import com.mungo.mapreduce.common.Dimensions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * 행렬곱 유사도 (3호) — 정규화 벡터의 블록 행렬곱 Q × Sᵀ = 코사인 유사도 행렬.
 * kNN(2호)이 클러스터 블로킹 근사라면, 이건 전량 정밀 계산(임계값 필터)이다.
 *
 * 사용법: hadoop jar mungo-mapreduce.jar com.mungo.mapreduce.matmul.MatMulDriver \
 *   [-Dmungo.vector.meta=/embeddings/{model}/{ver}/_meta.json] \
 *   <질의 벡터> <보호 벡터> <출력> [코사인 임계=0.8] [qBlocks=2] [sBlocks=8]
 *
 * 차원은 _meta.json(또는 -Dmungo.vector.dim)에서 읽어 모든 입력 벡터를 검증한다 (계약 §4 ③).
 */
public class MatMulDriver extends Configured implements Tool {

    @Override
    public int run(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("사용법: MatMulDriver <queries> <shelters> <output> [threshold] [qBlocks] [sBlocks]");
            return 2;
        }
        Configuration conf = getConf();
        Dimensions.configure(conf);
        conf.setDouble(BlockMultiplyReducer.THRESHOLD_KEY,
                args.length > 3 ? Double.parseDouble(args[3]) : 0.8);
        conf.setInt(BlockPairMapper.Q_BLOCKS_KEY, args.length > 4 ? Integer.parseInt(args[4]) : 2);
        conf.setInt(BlockPairMapper.S_BLOCKS_KEY, args.length > 5 ? Integer.parseInt(args[5]) : 8);

        Job job = Job.getInstance(conf, "matmul-similarity");
        job.setJarByClass(MatMulDriver.class);
        MultipleInputs.addInputPath(job, new Path(args[0]), TextInputFormat.class,
                BlockPairMapper.QueryMapper.class);
        MultipleInputs.addInputPath(job, new Path(args[1]), TextInputFormat.class,
                BlockPairMapper.ShelterMapper.class);
        job.setReducerClass(BlockMultiplyReducer.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);
        FileOutputFormat.setOutputPath(job, new Path(args[2]));
        return job.waitForCompletion(true) ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new Configuration(), new MatMulDriver(), args));
    }
}
