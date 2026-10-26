plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "et.alpha.nchat"
    // 37 because current androidx (core 1.19, Compose BOM 2026.09) refuses to
    // compile against less. Only the compile API moves: targetSdk stays 35, so
    // no runtime behaviour changes until that is bumped on its own.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "et.alpha.nchat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 code + resource shrinking (AGP 9.3 DSL). The default Android rules
            // are included automatically and every dependency ships consumer rules,
            // so the app's own rules in src/main/keepRules/ only silence references
            // to optional classes. Debug stays unoptimized so instrumented tests
            // and stack traces see the real code.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    // Exported Room schemas ship into the instrumented-test APK so
    // MigrationTestHelper can open an old schema and replay migrations against it.
    sourceSets {
        getByName("androidTest") {
            assets.directories.add("$projectDir/schemas")
        }
    }
}

// Room schema export, so migrations can be diffed in review.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The Compose BOM pins kotlinx-serialization-core to strictly 1.7.3, but
// room-testing's schema-bundle serializers are generated against 1.8.x and call
// a core method that 1.7.3 does not declare — MigrationTestHelper dies with
// AbstractMethodError while parsing schema JSON. Raise core to match, on the
// instrumented-test classpath only, so the app's own runtime keeps the version
// Compose pinned.
configurations.matching { it.name.contains("AndroidTest") }.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-serialization-core:1.12.0")
        force("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.12.0")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Declared rather than leaned on transitively: MeshRadar pauses its animation
    // clock on the lifecycle, so LocalLifecycleOwner is a direct dependency of the
    // UI now and not just something compose-ui happens to drag in.
    implementation(libs.androidx.lifecycle.runtime.compose)

    // DI
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Persistence
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Mesh transport
    implementation(libs.play.services.nearby)

    // App lock
    implementation(libs.androidx.biometric)

    // Crypto
    implementation(libs.tink.android)

    // QR pairing
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // Unit tests (JVM, JUnit 5) — router and codec logic lives here.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)

    // Instrumented tests (JUnit 4 — required by Espresso and Compose UI test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // mockk-android drags in JUnit 5 transitively. Instrumented tests run on
    // JUnit 4, so those jars are dead weight that only collide on packaging.
    androidTestImplementation(libs.mockk.android) {
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
    }
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
    // Room DAO and migration tests run on a device against a real SQLite.
    androidTestImplementation(libs.room.testing)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
