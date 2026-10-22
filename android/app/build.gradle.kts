import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val cloudApiSettings = Properties().apply {
    val settingsFile = rootProject.file("cloud-api.properties")
    if (settingsFile.isFile) settingsFile.inputStream().use { load(it) }
}
val configuredCloudOrigin = (providers.gradleProperty("cloudApiBaseUrl").orNull
    ?: cloudApiSettings.getProperty("cloudApiBaseUrl", "")).trim().trimEnd('/')
require(configuredCloudOrigin.isEmpty() || configuredCloudOrigin.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?"))) {
    "cloudApiBaseUrl must be an HTTPS origin without path"
}

android {
    namespace = "com.dayoudu.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dayoudu.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CLOUD_API_BASE_URL", "\"$configuredCloudOrigin\"")
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    kapt("androidx.room:room-compiler:2.8.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
