plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// Adresse du serveur familial (dépôt d'APK, découverte des adresses Traccar,
// écoute en direct) : hors du dépôt, fournie à la compilation.
// - CI : secret GitHub KW_FAMILY_HUB (ex. https://famille.exemple.fr) ;
// - en local : variable d'environnement KW_FAMILY_HUB, ou propriété Gradle
//   kwFamilyHub dans ~/.gradle/gradle.properties.
val familyHub: String = (System.getenv("KW_FAMILY_HUB")?.takeIf { it.isNotBlank() }
    ?: (project.findProperty("kwFamilyHub") as String?)
    ?: "").trim().trimEnd('/')

android {
    namespace = "fr.kinwaypoint.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.kinwaypoint.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 64
        versionName = "0.37.3"

        buildConfigField("String", "FAMILY_HUB", "\"$familyHub\"")

        // Téléphones réels uniquement (ARM) : divise par ~2 la taille due à la carte.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Clé de signature FIXE, fournie par la CI (secrets GitHub) ou en local par
    // variables d'environnement. Sans elle, chaque build CI aurait une clé de
    // debug différente et Android refuserait d'installer la mise à jour.
    val keystorePath = System.getenv("KW_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (keystorePath != null) {
            create("kinwaypoint") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KW_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KW_KEY_ALIAS")
                keyPassword = System.getenv("KW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("kinwaypoint")
            } else {
                // Build local de test uniquement : NE PAS distribuer cet APK.
                signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Vérification périodique des mises à jour, même appli fermée et téléphone en veille.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.biometric:biometric:1.1.0")
    // zxing-android-embedded tire une version de Fragment trop ancienne : lint
    // refuse le build release (InvalidFragmentVersionForActivityResult).
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR code : génération (zxing core) et scan caméra, sans Google Play Services.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Carte : MapLibre (open source, sans clé ni compte) + tuiles OpenStreetMap.
    implementation("org.maplibre.gl:android-sdk:11.10.0")
    // Notifications poussées (app fermée). Firebase ne transporte que le texte
    // de l'alerte ; les positions restent sur notre serveur.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // org.json réel pour les tests JVM (celui d'android.jar n'est qu'un bouchon).
    testImplementation("org.json:json:20240303")
}
