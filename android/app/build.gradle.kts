plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.darknesslord19.ghostbrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.darknesslord19.ghostbrowser"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "app"
    productFlavors {
        create("browser") {
            dimension = "app"
            manifestPlaceholders["appLabel"] = "Ghost Browser"
            buildConfigField("boolean", "CRAWLER_APP", "false")
        }
        create("crawler") {
            dimension = "app"
            applicationId = "com.darknesslord19.ghostcrawler"
            manifestPlaceholders["appLabel"] = "Ghost Tarayıcı"
            buildConfigField("boolean", "CRAWLER_APP", "true")
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
