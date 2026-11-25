plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vantora"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vantora"
        minSdk = 26
        targetSdk = 34
    }
}
