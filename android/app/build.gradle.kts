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
    versionCode = 1
    versionName = "0.1.0"
  }

  buildTypes {
    release { isMinifyEnabled = false }
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
