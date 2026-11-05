import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningProperties = Properties().apply {
    val file = rootProject.file(".release-signing/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseStoreFile = providers.provider { releaseSigningProperties.getProperty("storeFile") }
val releaseStorePassword = providers.provider { releaseSigningProperties.getProperty("storePassword") }
val releaseKeyAlias = providers.provider { releaseSigningProperties.getProperty("keyAlias") }
val releaseKeyPassword = providers.provider { releaseSigningProperties.getProperty("keyPassword") }
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isPresent }

android {
    namespace = "dev.apkpatcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.apkpatcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
