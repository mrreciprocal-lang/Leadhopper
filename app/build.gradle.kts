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
        versionCode = 25
        versionName = "1.7.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
