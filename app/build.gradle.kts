plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.ff6locke.btremoteex"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.ff6locke.btremoteex"
  minSdk = 28
  targetSdk = 35
  versionCode = 15
  versionName = "1.14"
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 signingConfigs { getByName("debug") { storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
 buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") } }
 kotlinOptions { jvmTarget = "17" }
}