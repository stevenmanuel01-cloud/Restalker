plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.restalker.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.restalker.mobile"
        minSdk = 26
        targetSdk = 36                      // 1.0.7: Google Play takes new apps only when they target Android 16 (since 2026-08-31)
        buildConfigField("boolean", "PLAY", "false")
        versionCode = 37 + (project.findProperty("rkCodeBump")?.toString()?.toIntOrNull() ?: 0)   // (tests: a "newer" copy to update to)
        versionName = "1.1.0"               // (1.0.0 comes after 2.15: the name starts again, the code above keeps counting; three parts - see Model.kt)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            val ks = System.getenv("RKM_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("RKM_KEYSTORE_PASSWORD")
                keyAlias = "restalker"
                keyPassword = System.getenv("RKM_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
        debug {
            val rel = signingConfigs.getByName("release")
            if (rel.storeFile != null) signingConfig = rel
        }
        // 1.0.7: the Google Play edition - the same app, but it never updates itself (Google Play's rule: an app from the store is
        // updated by the store alone) and asks for none of the permissions that would let it. "play" is what is uploaded to the
        // store (as an app bundle); "playDebug" is the same edition for the virtual-phone tests (-PrkEdition=play).
        create("play") {
            initWith(getByName("release"))
            buildConfigField("boolean", "PLAY", "true")
            matchingFallbacks += "release"
        }
        create("playDebug") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "PLAY", "true")
            matchingFallbacks += "debug"
        }
    }
    testBuildType = if (project.findProperty("rkEdition")?.toString() == "play") "playDebug" else "debug"
    // the app's own updater (and its test hook) belong to the GitHub edition only; the Play edition gets a stand-in that does nothing
    sourceSets["debug"].java.srcDir("src/github/java")
    sourceSets["release"].java.srcDir("src/github/java")
    sourceSets["play"].java.srcDir("src/playstore/java")
    sourceSets["playDebug"].java.srcDir("src/playstore/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["androidTest"].assets.srcDirs("../testsite")
    testOptions { animationsDisabled = true }
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")      // 1.4: scanning the desktop's link code (QR)

    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "playDebugImplementation"("androidx.compose.ui:ui-test-manifest")
}
