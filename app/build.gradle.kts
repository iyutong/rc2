plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "local.rc2.xbox"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "local.rc2.xbox"
        minSdk = 29
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 遥控器输入核心库：承载 protocol 订阅与报文解码，与 UI 完全解耦
    implementation(project(":rc2core"))
    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}