plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ydeclik.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ydeclik.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // Clé de signature fixe (fournie par les « Secrets » GitHub) : les mises à jour s'installent par-dessus l'ancienne version.
    val ksPath = System.getenv("YDEKLIK_KEYSTORE")
    val useKs = ksPath != null && file(ksPath).exists()
    if (useKs) {
        signingConfigs {
            create("ydeklik") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("YDEKLIK_STORE_PASSWORD")
                keyAlias = System.getenv("YDEKLIK_KEY_ALIAS")
                keyPassword = System.getenv("YDEKLIK_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
        debug {
            if (useKs) signingConfig = signingConfigs.getByName("ydeklik")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
