// app/build.gradle.kts — AGP 9.x built-in Kotlin
// org.jetbrains.kotlin.android is NOT applied here; AGP 9 provides it natively.
// The compose compiler plugin is still applied to enable Jetpack Compose.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace  = "com.gguf.ipc"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.gguf.ipc"
        minSdk        = 29
        targetSdk     = 36
        versionCode   = 5
        versionName   = "5.0"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -O3 -flto=thin -march=armv8.4a+dotprod+crc -fno-stack-protector")
                cFlags  ("-O3 -flto=thin -march=armv8.4a+dotprod+crc -fno-stack-protector")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_VULKAN=OFF",        // Requires SPIRV-Headers on host; disabled for CI
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

    buildFeatures { compose = true }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { useLegacyPackaging = true }
    }
}

// AGP 9 built-in Kotlin: use kotlin.compilerOptions instead of
// the removed kotlinOptions / jvmToolchain DSL from kotlin-android.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
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
    implementation("androidx.appcompat:appcompat:1.7.0")
}
