plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "local.codex.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "local.codex.mobile"
        minSdk = 30
        targetSdk = 35
        versionCode = 14
        versionName = "2.1.10"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(rootProject.layout.buildDirectory.dir("runtime/assets"))
    sourceSets["main"].jniLibs.srcDir(rootProject.layout.buildDirectory.dir("runtime/jniLibs"))
    androidResources { noCompress += "tgz" }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    signingConfigs {
        create("localRelease") {
            System.getenv("CODEX_MOBILE_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = "android"
            keyAlias = "codex-mobile"
            keyPassword = "android"
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("localRelease") }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.8")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
}
