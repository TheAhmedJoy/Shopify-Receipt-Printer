import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Store credentials and signing details come from the untracked local.properties. The Shopify token
// is baked into the APK so the kitchen device works with no setup; the client ID and secret are not.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localProp(key: String): String = localProps.getProperty(key, "").trim()

android {
    namespace = "com.example.shopify_receipt_printer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.shopify_receipt_printer"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SHOPIFY_STORE_DOMAIN", "\"${localProp("SHOPIFY_STORE_DOMAIN")}\"")
        buildConfigField("String", "SHOPIFY_ACCESS_TOKEN", "\"${localProp("SHOPIFY_APP_ACCESS_TOKEN")}\"")
        buildConfigField("String", "SHOPIFY_API_VERSION", "\"${localProp("SHOPIFY_APP_API_VERSION")}\"")
        buildConfigField("String", "TICKET_HEADER", "\"${localProp("TICKET_HEADER")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Only configured once a release keystore exists, so debug builds work without one
        if (localProp("RELEASE_STORE_FILE").isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(localProp("RELEASE_STORE_FILE"))
                storePassword = localProp("RELEASE_STORE_PASSWORD")
                keyAlias = localProp("RELEASE_KEY_ALIAS")
                keyPassword = localProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // Android's built-in org.json is stubbed in local unit tests, so pull in the real one
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
