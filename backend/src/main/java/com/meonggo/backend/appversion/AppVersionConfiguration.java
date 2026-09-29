package com.meonggo.backend.appversion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** {@code APP_ANDROID_*} 환경 변수를 {@link AppVersionPolicy} 로 읽는다. 잘못된 숫자는 기동 실패로 드러낸다. */
@Configuration
public class AppVersionConfiguration {
    static final String DEFAULT_STORE_URL = "https://m.onestore.co.kr/v2/ko-kr/app/0001009297";

    private final Environment environment;

    public AppVersionConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    AppVersionPolicy androidVersionPolicy() {
        int latest = number("APP_ANDROID_LATEST_VERSION_CODE");
        int minimum = number("APP_ANDROID_MIN_VERSION_CODE");
        if (minimum > latest && latest > 0) {
            throw new IllegalStateException(
                    "APP_ANDROID_MIN_VERSION_CODE must not exceed APP_ANDROID_LATEST_VERSION_CODE");
        }
        String storeUrl = environment.getProperty("APP_ANDROID_STORE_URL", DEFAULT_STORE_URL);
        if (storeUrl.isBlank() || !storeUrl.startsWith("https://")) {
            throw new IllegalStateException("APP_ANDROID_STORE_URL must be an https URL");
        }
        return new AppVersionPolicy(AppVersionPolicy.ANDROID, latest, minimum, storeUrl);
    }

    private int number(String name) {
        String raw = environment.getProperty(name, "0").strip();
        try {
            int value = Integer.parseInt(raw);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(name + " must be a non-negative integer");
        }
    }
}
