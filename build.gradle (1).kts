plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.vantora.app"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.vantora.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }

    // Release signing is configured only when CI provides a keystore through environment variables.
    val ksFile = System.getenv("VANTORA_KEYSTORE_FILE")
    if (ksFile != null) {
        signingConfigs.create("release") {
            storeFile = file(ksFile)
            storePassword = System.getenv("VANTORA_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("VANTORA_KEY_ALIAS")
            keyPassword = System.getenv("VANTORA_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // The QEMU engine ships as an executable named lib*.so and must be extracted to nativeLibraryDir.
    packaging { jniLibs { useLegacyPackaging = true } }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
}
