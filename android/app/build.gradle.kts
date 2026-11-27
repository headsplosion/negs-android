plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The film data the engine reads, copied from the repository's data directory.
val filmData = layout.buildDirectory.dir("filmData")
val copyFilmData = tasks.register<Sync>("copyFilmData") {
    from("../../data") { exclude("profile-sources/**") ; into("data") }
    from("../../LICENSE")
    into(filmData)
}

android {
    namespace = "com.headsplosion.nugs"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.headsplosion.nugs"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.$versionCode"
        ndk { abiFilters += "arm64-v8a" }
    }
    // One permanent key lets each build install over the last, keeping presets.
    // It lives in a GitHub secret; without it the build uses a throwaway key.
    System.getenv("NUGS_KEYSTORE")?.let { path ->
        signingConfigs.getByName("debug") {
            storeFile = file(path)
            storePassword = "nugs-signing"
            keyAlias = "nugs"
            keyPassword = "nugs-signing"
        }
    }
    sourceSets["main"].assets.srcDir(filmData)
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

tasks.named("preBuild") { dependsOn(copyFilmData) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
