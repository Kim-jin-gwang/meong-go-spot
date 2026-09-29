package com.mungo.mapreduce.jaccard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;

/**
 * 태그 하나를 공유하는 id들로 후보쌍을 만든다 — 쌍 키는 사전순 정렬로 정규화해
 * (a,b)와 (b,a)가 같은 키가 되게 한다. 값 "1" 하나가 공유 태그 1개를 뜻한다.
 *
 * 후보쌍 수는 태그 빈도의 제곱 — 저변별 태그를 입력 단계에서 제거하는 것이
 * 이 알고리즘의 비용 통제 수단이다 (tools/make_jaccard_input.py 참조).
 */
public class CandidatePairReducer extends Reducer<Text, Text, Text, Text> {

    private static final Text ONE = new Text("1");
    private final Text outKey = new Text();

    @Override
    protected void reduce(Text key, Iterable<Text> values, Context context)
            throws IOException, InterruptedException {
        List<String> members = new ArrayList<>();
        for (Text value : values) {
            members.add(value.toString()); // "id|setSize"
        }
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                String a = members.get(i);
                String b = members.get(j);
                // 자기쌍(J=1.0 오염) 방지 — 집합 크기가 아니라 id 부분만 비교해야 정확하다
                if (a.substring(0, a.indexOf('|')).equals(b.substring(0, b.indexOf('|')))) {
                    continue;
                }
                outKey.set(a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a);
                context.write(outKey, ONE);
            }
        }
    }
}
