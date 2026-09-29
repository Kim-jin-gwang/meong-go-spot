package com.meonggo.backend.chat.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 채팅 본문의 욕설을 같은 길이의 {@code *} 로 가린다. C4 저장 직전에 적용하므로 저장·조회·푸시 어디에도 원문이 남지 않는다.
 *
 * <p>비교는 글자(문자)만 남기고 소문자로 만든 문자열에서 부분 문자열로 한다 — "씨 발", "씨.발", "씨1발" 처럼 사이에 공백·문장부호·숫자를 끼워도 걸린다. 가릴
 * 때는 원문에서 그 구간에 해당하는 코드 포인트를 모두 {@code *} 로 바꾸므로 사이에 끼운 것도 함께 사라진다.
 *
 * <p>목록은 {@code chat/profanity-ko.txt}. 짧아서 일상 낱말과 겹치는 단어("새끼", "씹")는 목록에 넣지 않는 것으로 오탐을 막는다 —
 * 정규식·형태소 분석은 두지 않는다. 멱등 해시({@code request_hash})는 원문으로 계산하므로 마스킹은 멱등 판정에 영향을 주지 않는다.
 */
@Component
public class ProfanityMasker {
    static final String DEFAULT_LIST = "chat/profanity-ko.txt";
    private static final char MASK = '*';

    private final List<String> terms;

    public ProfanityMasker() {
        this(loadTerms(DEFAULT_LIST));
    }

    ProfanityMasker(Collection<String> rawTerms) {
        List<String> normalized = new ArrayList<>();
        for (String raw : rawTerms) {
            String term = normalize(raw).text();
            if (!term.isEmpty() && !normalized.contains(term)) normalized.add(term);
        }
        // 긴 단어를 먼저 보아 "씨발놈" 이 "씨발" 로 반쪽만 가려지지 않게 한다 (결과는 같지만 순서를 고정해 둔다).
        normalized.sort(Comparator.comparingInt(String::length).reversed());
        this.terms = List.copyOf(normalized);
    }

    /** 가릴 것이 없으면 같은 인스턴스를 돌려준다. */
    public String mask(String content) {
        if (content == null || content.isEmpty() || terms.isEmpty()) return content;
        Normalized normalized = normalize(content);
        boolean[] masked = new boolean[content.length()];
        boolean any = false;
        for (String term : terms) {
            int from = 0;
            while (true) {
                int at = normalized.text().indexOf(term, from);
                if (at < 0) break;
                int start = normalized.originalStart(at);
                int end = normalized.originalEnd(at + term.length() - 1);
                for (int i = start; i < end; i++) masked[i] = true;
                any = true;
                from = at + 1;
            }
        }
        if (!any) return content;
        StringBuilder out = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); ) {
            int codePoint = content.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (masked[i]) out.append(MASK);
            else out.appendCodePoint(codePoint);
            i += width;
        }
        return out.toString();
    }

    public int termCount() {
        return terms.size();
    }

    /** 글자만 남긴 소문자 문자열과, 그 각 글자가 원문의 어느 char 구간에서 왔는지. 숫자는 끼움 우회("씨1발")에 쓰이므로 글자로 치지 않는다. */
    private static Normalized normalize(String value) {
        StringBuilder text = new StringBuilder(value.length());
        List<int[]> spans = new ArrayList<>();
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (Character.isLetter(codePoint)) {
                String lower = new String(Character.toChars(codePoint)).toLowerCase(Locale.ROOT);
                for (int k = 0; k < lower.length(); k++) {
                    text.append(lower.charAt(k));
                    spans.add(new int[] {i, i + width});
                }
            }
            i += width;
        }
        return new Normalized(text.toString(), spans);
    }

    static List<String> loadTerms(String location) {
        try {
            String body =
                    new ClassPathResource(location).getContentAsString(StandardCharsets.UTF_8);
            List<String> terms = new ArrayList<>();
            for (String line : body.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                terms.add(trimmed);
            }
            return terms;
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read profanity list " + location, exception);
        }
    }

    private record Normalized(String text, List<int[]> spans) {
        int originalStart(int index) {
            return spans.get(index)[0];
        }

        int originalEnd(int index) {
            return spans.get(index)[1];
        }
    }
}
