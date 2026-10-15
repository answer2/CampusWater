plugins {
    alias(libs.plugins.android.application)
    // gradle.properties 里关掉了 AGP 内置 Kotlin（版本太老，读不了 backdrop 的 2.3 元数据），
    // 所以这里要重新显式应用 KGP 与 Compose 编译器插件，两者版本必须一致。
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.answer.campuswater"
    // 经典 DSL（见 gradle.properties 的 android.newDsl）：不能再用 release(36){minorApiLevel}
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.answer.campuswater"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    // 支付宝 App 支付。AliPayLauncher 用反射调用，所以即使移除这一行也能编译；
    // 但没有它就无法真正拉起收银台。
    implementation(libs.alipay.sdk)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)

    // Compose + Miuix（HyperOS 风格组件库）
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.miuix)
    // Liquid Glass：把身后的画面画进图层，再用 RenderEffect 做模糊 + 透镜折射
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.compose.ui.util)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}