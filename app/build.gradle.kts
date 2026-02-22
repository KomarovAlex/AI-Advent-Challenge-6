import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.koalexse.aichallenge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "ru.koalexse.aichallenge"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Загружаем secrets.properties
    val secretsFile = rootProject.file("local.properties")
    val secrets = Properties()
    if (secretsFile.exists()) {
        secrets.load(FileInputStream(secretsFile))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            buildConfigField(
                "String",
                "OPENAI_API_KEY",
                "\"${secrets.getProperty("OPENAI_API_KEY", "")}\""
            )
            buildConfigField(
                "String",
                "OPENAI_URL",
                "\"${
                    secrets.getProperty(
                        "OPENAI_URL",
                        "https://api.openai.com/v1/chat/completions"
                    )
                }\""
            )
            buildConfigField(
                "String",
                "OPENAI_MODEL",
                "\"${secrets.getProperty("OPENAI_MODEL", "gpt-3.5-turbo")}\""
            )
        }
        debug {
            buildConfigField(
                "String",
                "OPENAI_API_KEY",
                "\"${secrets.getProperty("OPENAI_API_KEY", "")}\""
            )
            buildConfigField(
                "String",
                "OPENAI_URL",
                "\"${
                    secrets.getProperty(
                        "OPENAI_URL",
                        "https://api.openai.com/v1/chat/completions"
                    )
                }\""
            )
            buildConfigField(
                "String",
                "OPENAI_MODEL",
                "\"${secrets.getProperty("OPENAI_MODEL", "gpt-3.5-turbo")}\""
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true  // Включаем генерацию BuildConfig
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.accompanist.systemuicontroller)
    implementation(libs.multiplatform.markdown.renderer.m3)

    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}