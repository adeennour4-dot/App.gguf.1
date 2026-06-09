
name: Build APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          # Note: do NOT use 'cache: gradle' here — setup-gradle below handles caching.

      # -----------------------------------------------------------------------
      # FIX: Use gradle/actions/setup-gradle instead of relying on the wrapper
      # to download Gradle. This pre-installs Gradle from the GitHub Actions
      # tool cache and avoids the services.gradle.org download timeout entirely.
      # -----------------------------------------------------------------------
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: wrapper   # Reads version from gradle-wrapper.properties (9.5.1)
          cache-read-only: false

      # v4 is current — v3 is deprecated
      - name: Setup Android SDK
        uses: android-actions/setup-android@v4

      - name: Install SDK platform + build tools + NDK r29 + CMake
        run: |
          sdkmanager --install \
            "platforms;android-36" \
            "build-tools;36.0.0" \
            "ndk;29.0.14206865" \
            "cmake;3.22.1"
          echo "ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865" >> $GITHUB_ENV

      - name: Write local.properties
        run: |
          echo "sdk.dir=$ANDROID_HOME" > local.properties
          echo "ndk.dir=$ANDROID_HOME/ndk/29.0.14206865" >> local.properties

      - name: Cache CMake deps (llama.cpp FetchContent)
        uses: actions/cache@v4
        with:
          path: |
            app/.cxx
            app/build/_deps
          key: cmake-deps-b9542-${{ runner.os }}-${{ hashFiles('app/src/main/cpp/CMakeLists.txt') }}
          restore-keys: cmake-deps-b9542-

      - name: Make gradlew executable
        run: chmod +x gradlew

      - name: Build debug APK
        run: ./gradlew assembleDebug --stacktrace --no-daemon
        env:
          GRADLE_OPTS: "-Dorg.gradle.jvmargs=-Xmx4g"

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: GGUF-ZeroCopy-v5-debug
          path: app/build/outputs/apk/debug/*.apk
          retention-days: 30
