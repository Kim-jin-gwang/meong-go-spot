package com.mungo.mapreduce.knn;

import com.mungo.mapreduce.common.VectorUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

/**
 * 클러스터(방) 하나의 레코드를 모아 질의(Q)마다 보호 동물(S) Top-K를 계산한다.
 * 출력: qid \t rank \t sid \t distance — match_candidate의 원형.
 *
 * 방 크기 × 차원만큼 메모리를 쓴다 — 현재 규모(방당 수백~수천 벡터)엔 충분하고,
 * 방이 수십만으로 커지면 방 분할(2차 키)로 확장한다 (README 부채).
 */
public class KnnReducer extends Reducer<IntWritable, Text, Text, Text> {

    static final String TOP_K_KEY = "knn.top.k";

    private int topK;
    private final Text outKey = new Text();
    private final Text outValue = new Text();

    private record Entry(String id, double[] vector) {}

    private record Scored(String id, double distance) {}

    @Override
    protected void setup(Context context) {
        topK = context.getConfiguration().getInt(TOP_K_KEY, 20);
    }

    @Override
    protected void reduce(IntWritable key, Iterable<Text> values, Context context)
            throws IOException, InterruptedException {
        List<Entry> queries = new ArrayList<>();
        List<Entry> shelters = new ArrayList<>();
        for (Text value : values) {
            String[] parts = value.toString().split("\\|", 3);
            Entry entry = new Entry(parts[1], VectorUtil.parse(parts[2]));
            if (parts[0].charAt(0) == 'Q') {
                queries.add(entry);
            } else {
                shelters.add(entry);
            }
        }
        if (queries.isEmpty() || shelters.isEmpty()) {
            return;
        }

        for (Entry query : queries) {
            // 최대 힙(거리 큰 것이 루트) 크기 K 유지 — 방 전체 정렬 없이 Top-K
            PriorityQueue<Scored> heap =
                    new PriorityQueue<>((a, b) -> Double.compare(b.distance, a.distance));
            for (Entry shelter : shelters) {
                double d = VectorUtil.squaredDistance(query.vector(), shelter.vector());
                if (heap.size() < topK) {
                    heap.add(new Scored(shelter.id(), d));
                } else if (d < heap.peek().distance()) {
                    heap.poll();
                    heap.add(new Scored(shelter.id(), d));
                }
            }
            List<Scored> ranked = new ArrayList<>(heap);
            ranked.sort((a, b) -> Double.compare(a.distance(), b.distance()));
            for (int rank = 0; rank < ranked.size(); rank++) {
                Scored scored = ranked.get(rank);
                outKey.set(query.id());
                outValue.set((rank + 1) + "\t" + scored.id() + "\t" + Math.sqrt(scored.distance()));
                context.write(outKey, outValue);
            }
        }
    }
}
