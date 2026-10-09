plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "cat.rumb.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "cat.rumb.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 173
        versionName = "1.90.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // A Google Play licensing public key is public application metadata, not a signing key.
        val playBillingPublicKey = providers.gradleProperty("playBillingPublicKey")
            .orElse(providers.environmentVariable("RUMB_PLAY_BILLING_PUBLIC_KEY"))
            .orElse(providers.fileContents(rootProject.layout.projectDirectory.file("play-billing-public-key.txt")).asText)
            .get().trim()
        require(playBillingPublicKey.matches(Regex("[A-Za-z0-9+/=]+"))) {
            "Google Play licensing public key must be Base64"
        }
        buildConfigField("String", "PLAY_BILLING_PUBLIC_KEY", "\"$playBillingPublicKey\"")
        val playAccessCodeHash = providers.fileContents(
            rootProject.layout.projectDirectory.file("play-access-code-sha256.txt"),
        ).asText.get().trim()
        require(playAccessCodeHash.matches(Regex("[a-f0-9]{64}"))) {
            "Free access code SHA-256 must be a hexadecimal digest"
        }
        buildConfigField("String", "PLAY_ACCESS_CODE_SHA256", "\"$playAccessCodeHash\"")
    }

    // Google Play delivers updates itself. Keep the APK updater in the GitHub source set only.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }

    // Stable release signing. In CI the keystore is decoded from a secret and its path/passwords
    // are provided via env vars, so every published APK is signed with the SAME key — updates
    // install over each other without an uninstall. Locally (no env) release falls back to debug.
    val releaseKeystore = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.navigation.compose)
    implementation(libs.viewpager2)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)

    implementation(libs.maplibre)
    implementation(libs.maplibre.annotation)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    "githubImplementation"(libs.nanohttpd)
    implementation(libs.okhttp.logging)
    implementation(libs.serialization.json)
    implementation(libs.security.crypto)

    implementation(libs.coil.compose)
    implementation(libs.documentfile)
    "playImplementation"(libs.billing)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj)
    testImplementation(libs.coroutines.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
