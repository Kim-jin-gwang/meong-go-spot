import java.util.Properties

// 릴리스 서명 정보는 저장소에 두지 않는다. android/keystore.properties 는 .gitignore 대상이고
// keystore.properties.example 을 복사해 각자 채운다. 파일이 없으면 릴리스는 서명 없이 빌드되므로
// CI 와 다른 팀원의 빌드는 그대로 동작한다.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use(::load)
        }
    }
val hasReleaseSigning = !keystoreProperties.getProperty("storeFile").isNullOrBlank()

plugins {
    id("com.android.application")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

// Firebase 프로젝트 설정은 로컬·CI Secret으로 공급한다. 설정 파일이 없는 일반 CI에서도
// 단위 테스트와 lint를 실행할 수 있도록 파일이 있을 때만 리소스 생성 플러그인을 적용한다.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.hotdog.meonggocuisine"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hotdog.meonggocuisine"
        minSdk = 26
        targetSdk = 36
        // 릴리즈마다 올린다 (docs/deploy-guide.md "릴리즈 버전").
        // versionCode 는 Play Store 가 요구하는 단조 증가 정수라 같은 값으로 두 번 업로드할 수 없다.
        versionCode = 9
        versionName = "1.6.0"

        // 유사도 분석 출시 여부. 2026-09-15 부터 기본 true — 매칭 엔진·worker 가 서버 1 에 상주하고
        // (docs/deploy-guide.md "매칭 엔진"), 집계 규칙 max·임계값 0.60 이 확정됐다 (docs/data-ai-interface.md 0-2·0-3).
        // 켜기 전 조건 세 가지(집계 규칙·임계값·엔진 배치)가 모두 충족돼 게이트를 내렸다.
        // 끄려면 -PMATCHING_ENABLED=false — 화면과 경로는 그대로 두고 진입만 막는다.
        val matchingEnabled = providers.gradleProperty("MATCHING_ENABLED").orElse("true")
        buildConfigField("boolean", "MATCHING_ENABLED", matchingEnabled.get())
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            val apiBaseUrl = providers.gradleProperty("API_BASE_URL").orElse("http://10.0.2.2:8080/api/v1/")
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.get()}\"")
            // 공공 이미지 캐시 프록시(서버 1 nginx /img/). 로컬 백엔드에는 없으므로 기본은 비움 = 원본 직접.
            // 운영 서버를 보는 디버그 빌드는 -PIMAGE_PROXY_BASE_URL=https://api.meonggo.shop/img/ 로 켠다.
            val imageProxy = providers.gradleProperty("IMAGE_PROXY_BASE_URL").orElse("")
            buildConfigField("String", "IMAGE_PROXY_BASE_URL", "\"${imageProxy.get()}\"")
        }
        getByName("release") {
            // 운영 종단 (docs/deploy-guide.md "TLS 종단"). 다른 서버를 쓰려면
            // -PAPI_BASE_URL=... 로 덮어쓴다. 릴리스는 평문을 허용하지 않으므로 https 만 쓴다.
            val apiBaseUrl =
                providers.gradleProperty("API_BASE_URL").orElse("https://api.meonggo.shop/api/v1/")
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.get()}\"")
            // 공공 이미지는 서버 1 nginx 캐시 프록시(/img/)를 거친다 — 원본(openapi.animal.go.kr)은
            // TTFB 중앙값 0.7초·p90 6초에 캐시 헤더가 없다 (docs/deploy-guide.md "공공 이미지 캐시 프록시").
            val imageProxy =
                providers.gradleProperty("IMAGE_PROXY_BASE_URL").orElse("https://api.meonggo.shop/img/")
            buildConfigField("String", "IMAGE_PROXY_BASE_URL", "\"${imageProxy.get()}\"")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        // NullSafeMutableLiveData 검사는 Kotlin analysis API 불일치로 lint 분석 전체를 중단시킨다.
        // 이 앱은 LiveData를 쓰지 않고 StateFlow만 사용하므로 끄더라도 잃는 검사가 없다.
        disable += "NullSafeMutableLiveData"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    // 업로드 전 사진 축소 때 EXIF 방향을 읽는다 (core/media/UploadPhotoShrinker)
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // 시작 화면. minSdk 26 이라 Android 12 의 시스템 시작 화면만으로는 구버전에서 아무것도
    // 보이지 않는다. 이 라이브러리가 같은 모양을 아래 버전까지 내려 준다.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("com.google.dagger:hilt-android:2.57.1")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    ksp("com.google.dagger:hilt-android-compiler:2.57.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
