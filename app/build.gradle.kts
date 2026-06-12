plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace  = "com.gguf.ipc"
    compileSdk = 36
    ndkVersion = "29.0.14206865"          // NDK r29 stable

    defaultConfig {
        applicationId = "com.gguf.ipc"
        minSdk        = 27
        targetSdk     = 36
        versionCode   = 6
        versionName   = "6.0"

        externalNativeBuild {
            cmake {
                // Performance flags: ThinLTO, ARMv8.7-a with NEON FP16, SVE2 for Exynos 2200+
                cppFlags("-std=c++17 -O3 -flto=thin -march=armv8.7-a+dotprod+i8mm+fp16+sve2 -fno-stack-protector")
                cFlags  ("-O3 -flto=thin -march=armv8.7-a+dotprod+i8mm+fp16+sve2 -fno-stack-protector")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DGGML_BACKEND_DL=OFF"
                )
                abiFilters += "arm64-v8a"
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

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Use legacy packaging for zero-copy shared memory
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // LiteRT-LM (Google AI Edge) - for .tflite/.litertlm model support
    // Apache 2.0 license - allows commercial use
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
}
