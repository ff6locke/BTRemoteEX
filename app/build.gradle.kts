plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.google.protobuf") }
android {
 namespace = "com.ff6locke.btremoteex"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.ff6locke.btremoteex"
  minSdk = 28
  targetSdk = 35
  versionCode = 16
  versionName = "1.15"
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 signingConfigs { getByName("debug") { storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
 buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") } }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 implementation("com.google.protobuf:protobuf-javalite:4.36.2")
}
protobuf {
 protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
 generateProtoTasks {
  all().configureEach { task ->
   task.builtins { maybeCreate("java").apply { option("lite") } }
  }
 }
}