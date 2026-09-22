import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.repovoyage.sign"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.repovoyage.sign"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // GO 3S 固件只提供 arm64-v8a 原生库（与 Demo 一致）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 双 flavor：training 为采集/训练通道（独立包名 + .training 后缀），production 为成品
    flavorDimensions += "channel"
    productFlavors {
        create("training") {
            applicationIdSuffix = ".training"
            versionNameSuffix = "-training"
        }
        create("production") {
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.inskmp.camera)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
