plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace  = "com.gguf.ipc"
    compileSdk = 36
    ndkVersion = "29.0.14206865"          // NDK r29 stable

    defaultConfig {
        applicationId = "com.gguf.ipc"
        minSdk        = 29                 // FIXED: Prevents SharedMemory API compilation failures
        targetSdk     = 36
        versionCode   = 5
        versionName   = "5.0"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -O3 -flto=thin -march=armv8.4a+dotprod+crc -fno-stack-protector")
                cFlags  ("-O3 -flto=thin -march=armv8.4a+dotprod+crc -fno-stack-protector")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    // Vulkan OFF: llama.cpp's Vulkan backend requires SPIRV-Headers
                    // on the HOST at compile time (to compile GLSL shaders).
                    // GitHub Actions runners don't have it — enabling Vulkan here
                    // causes: "Could not find SPIRV-Headers"
                    // llama.cpp will still use the Android Vulkan loader at runtime
                    // via the CPU/GPU backend; this only disables compile-time shader compilation.
                    "-DGGML_VULKAN=OFF",
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

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        
        // FIXED: Bypasses manifest extractNativeLibs crash and forces JNI extraction
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
}
