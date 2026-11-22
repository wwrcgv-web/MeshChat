plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // Le namespace reste celui du code Kotlin : le renommer imposerait de déplacer tous les
    // fichiers. Seul applicationId identifie l'app auprès du Play Store, et il est définitif.
    namespace = "com.example.meshchat"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.wwrcgv.samchat"
        minSdk = 26
        targetSdk = 35
        // Numéro de build GitHub : une mise à jour doit toujours avoir un versionCode supérieur
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.2.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        buildConfigField("String", "RELAY_TOKEN", "\"${System.getenv("RELAY_TOKEN") ?: ""}\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Signature de production : le Keystore et ses mots de passe viennent de l'environnement
    // (secrets GitHub), jamais du dépôt. Absent en local : la build release reste non signée.
    signingConfigs {
        val storePath = System.getenv("RELEASE_KEYSTORE")
        if (!storePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // QR code : création et lecture avec la caméra
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
}
