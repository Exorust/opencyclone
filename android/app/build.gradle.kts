plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ai.opencyclon"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.opencyclon"
        minSdk = 31  // Android 11+ for SurfaceControl.screenshot()
        targetSdk = 36
        versionCode = 201
        versionName = "2.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    lint {
        // System app uses hidden APIs and runs on a known SDK — NewApi is a false positive.
        abortOnError = false
    }

    // One flavour: the app gets its privileges from Shizuku or wireless ADB.
    // The name stays because build commands, setup.sh and the release use assembleStandardDebug.
    flavorDimensions += "privilege"
    productFlavors {
        create("standard") {
            dimension = "privilege"
            applicationIdSuffix = ".standard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed here; the release workflow re-signs with the release key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        prefab = true
    }

    packaging {
        resources {
            // Bouncy Castle OSGI manifests conflict
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            // Recognizer libraries are packaged (decision 13); the model download exists only for arm64-v8a.
            excludes += listOf("armeabi-v7a", "x86", "x86_64").flatMap {
                listOf("lib/$it/libonnxruntime.so", "lib/$it/libsherpa-onnx-jni.so")
            }
            // The ONNX Runtime Java API is unused (no code imports ai.onnxruntime); only libonnxruntime.so,
            // which libsherpa-onnx-jni.so links against, is needed from that dependency.
            excludes += "**/libonnxruntime4j_jni.so"
        }
    }
}

dependencies {
    // Hidden APIs accessed via reflection at runtime — no compile-time stubs needed

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device streaming ASR. Sherpa 8.5.1's JNI library is linked against
    // ONNX Runtime 1.24.3; exclude the stale transitive lib-onnx package.
    implementation("com.bihe0832.android:lib-sherpa-onnx:8.5.1") {
        exclude(group = "com.bihe0832.android", module = "lib-onnx")
    }
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")

    // Lifecycle (process-wide foreground/background detection for OverlayService)
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.8.7")

    // OkHttp for LLM API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.json:json:20240303")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Shizuku SDK (user-space shell service via bindUserService)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ADB pairing native (BoringSSL for SPAKE2 + HKDF + AES-GCM)
    implementation("io.github.vvb2060.ndk:boringssl:20250114")
    implementation("org.lsposed.libcxx:libcxx:27.0.12077973")
    // ADB key management (X.509 cert generation) + TLS with exportKeyingMaterial
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    // 2.6+ fixes the crash on 16 KB page phones (unaligned GNU_RELRO, google/conscrypt#1413).
    implementation("org.conscrypt:conscrypt-android:2.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("app.cash.turbine:turbine:1.2.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
