package com.meonggo.backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 채팅 욕설 마스킹 — 목록의 단어를 같은 길이의 * 로 바꾸고, 일상 낱말은 건드리지 않는다. */
class ProfanityMaskerTest {
    private final ProfanityMasker masker = new ProfanityMasker();

    @Test
    void masksListedWordsWithSameLengthStars() {
        assertThat(masker.mask("이 씨발 놈아")).isEqualTo("이 ****아"); // "씨발놈" 결합형이 공백째 걸린다
        assertThat(masker.mask("이 씨발 소리")).isEqualTo("이 ** 소리");
        assertThat(masker.mask("병신같은 소리")).isEqualTo("**같은 소리");
        assertThat(masker.mask("FUCK you")).isEqualTo("**** you");
    }

    @Test
    void catchesWordsSplitBySpacesOrPunctuation() {
        // 사이에 끼운 공백·문장부호·숫자까지 한 구간으로 가린다
        assertThat(masker.mask("씨 발")).isEqualTo("***");
        assertThat(masker.mask("씨.발.놈")).isEqualTo("*****");
        assertThat(masker.mask("씨1발")).isEqualTo("***");
        assertThat(masker.mask("씨12발놈아")).isEqualTo("*****아");
        assertThat(masker.mask("ㅅ ㅂ 진짜")).isEqualTo("*** 진짜");
    }

    @Test
    void leavesEverydayWordsAndPetTalkAlone() {
        // 반려동물 서비스다 — "새끼 고양이" 가 가려지면 안 된다
        String[] safe = {
            "새끼 고양이 세 마리를 보호하고 있어요",
            "사료를 씹다가 뱉었어요",
            "리드줄을 졸라매지 마세요",
            "걸레로 발을 닦아 줬어요",
            "미친 듯이 뛰어다녀요",
            "5개년 계획처럼 오래 키웠어요",
        };
        for (String text : safe) {
            assertThat(masker.mask(text)).as(text).isSameAs(text);
        }
    }

    @Test
    void keepsSurrogatePairsIntactAroundMasks() {
        assertThat(masker.mask("🐶 씨발 🐱")).isEqualTo("🐶 ** 🐱");
    }

    @Test
    void loadsTheBundledListAndSkipsComments() {
        assertThat(masker.termCount()).isGreaterThan(30);
        assertThat(new ProfanityMasker(List.of("# 주석", "", "  바보  ")).mask("바 보야"))
                .isEqualTo("***야");
    }

    @Test
    void returnsInputUnchangedWhenNothingMatches() {
        String text = "안녕하세요, 비슷한 아이를 보호하고 있어요.";
        assertThat(masker.mask(text)).isSameAs(text);
        assertThat(masker.mask("")).isEmpty();
        assertThat(masker.mask(null)).isNull();
    }
}
