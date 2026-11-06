plugins {
    // 复用根工程已加载的 AGP（com.android.tools.build:gradle），不额外解析 marker
    id("com.android.library")
}

android {
    namespace = "local.rc2.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// 本模块刻意不引入任何依赖：只用 android.os / android.view / android.util。
// 迁移时整包拷走即可，也可以 `./gradlew :rc2core:assembleRelease` 出 AAR。

dependencies {
    testImplementation(libs.junit)
}
