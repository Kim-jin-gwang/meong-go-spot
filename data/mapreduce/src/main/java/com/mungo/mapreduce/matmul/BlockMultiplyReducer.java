package com.mungo.mapreduce.matmul;

import com.mungo.mapreduce.common.VectorUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

/**
 * 블록 쌍 (qBlock,sBlock) 하나를 받아 Q블록 × S블록ᵀ 부분 행렬곱을 수행한다.
 * 정규화된 행이므로 내적 = 코사인 유사도. 임계값 이상만 출력해 q×s 전량 출력을 막는다.
 * 출력: qid \t sid \t cosine
 */
public class BlockMultiplyReducer extends Reducer<Text, Text, Text, Text> {

    static final String THRESHOLD_KEY = "matmul.cosine.threshold";

    private double threshold;
    private final Text outKey = new Text();
    private final Text outValue = new Text();

    private record Row(String id, double[] vector) {}

    @Override
    protected void setup(Context context) {
        threshold = context.getConfiguration().getDouble(THRESHOLD_KEY, 0.8);
    }

    @Override
    protected void reduce(Text key, Iterable<Text> values, Context context)
            throws IOException, InterruptedException {
        List<Row> queries = new ArrayList<>();
        List<Row> shelters = new ArrayList<>();
        for (Text value : values) {
            String[] parts = value.toString().split("\\|", 3);
            Row row = new Row(parts[1], VectorUtil.parse(parts[2]));
            if (parts[0].charAt(0) == 'Q') {
                queries.add(row);
            } else {
                shelters.add(row);
            }
        }
        for (Row q : queries) {
            for (Row s : shelters) {
                double dot = 0;
                double[] a = q.vector();
                double[] b = s.vector();
                for (int i = 0; i < a.length; i++) {
                    dot += a[i] * b[i];
                }
                if (dot >= threshold) {
                    outKey.set(q.id());
                    outValue.set(s.id() + "\t" + dot);
                    context.write(outKey, outValue);
                }
            }
        }
    }
}
