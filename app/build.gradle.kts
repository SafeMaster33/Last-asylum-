plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.safemaster.lastasylum"; compileSdk = 35
 defaultConfig { applicationId = "com.safemaster.lastasylum"; minSdk = 30; targetSdk = 32; versionCode = 18; versionName = "0.9.0-test" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.google.mlkit:text-recognition:16.0.1") }
