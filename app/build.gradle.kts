plugins {
    id("com.android.application")
}

android {
    namespace = "pl.altamer.odczytddd"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.altamer.odczytddd"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-prototype"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
