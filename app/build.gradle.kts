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
        versionCode = 23
        versionName = "1.7.4"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}
