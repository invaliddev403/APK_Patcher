plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.apkpatcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.apkpatcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")
        }
    }
}

dependencies {
    implementation("org.smali:dexlib2:2.5.2")
    implementation("com.android.tools.build:apksig:8.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
