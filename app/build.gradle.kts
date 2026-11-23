plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.ff6locke.btremoteex"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.ff6locke.btremoteex"
  minSdk = 28
  targetSdk = 35
  versionCode = 6
  versionName = "1.5"
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget = "17" }
}