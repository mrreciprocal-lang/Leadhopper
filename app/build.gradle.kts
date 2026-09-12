plugins {
    id("com.android.application")
}

android {
    namespace = "com.kyle.leadhopper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kyle.leadhopper"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.6.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}
