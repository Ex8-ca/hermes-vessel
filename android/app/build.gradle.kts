plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.chillygeek.hermesvessel"
  compileSdk = 36

  buildFeatures { compose = true }

  defaultConfig {
    applicationId = "com.chillygeek.hermesvessel"
    minSdk = 26
    targetSdk = 36
    // CI passes -PvesselVersionName=<tag without v> -PvesselVersionCode=<run number> for releases.
    versionCode = (findProperty("vesselVersionCode") as String?)?.toInt() ?: 1
    versionName = (findProperty("vesselVersionName") as String?) ?: "0.1.0"
  }

  // Release signing: the key comes from the environment (a GitHub Actions secret in CI,
  // ~/.android/keystores/hermes-vessel-release.properties locally) and is never committed.
  val releaseKeystore = System.getenv("VESSEL_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }
  signingConfigs {
    if (releaseKeystore != null) {
      create("release") {
        storeFile = file(releaseKeystore)
        storePassword = System.getenv("VESSEL_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("VESSEL_KEY_ALIAS")
        keyPassword = System.getenv("VESSEL_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      // Without the release key (forks, pull requests) a release build is debug-signed: installable, never published.
      signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  implementation(libs.androidx.compose.material3)
}
