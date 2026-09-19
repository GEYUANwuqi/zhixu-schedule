import java.util.zip.ZipFile

/** Native Core ABIs the application packages. */
val nativeAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

/**
 * Marker derived from the pinned release certificate digest, computed the same way the
 * native library does at its own build time. The marker is embedded there in clear text
 * and is the only stable way to tell whether an artifact was built for this certificate
 * digest, so both sides must keep computing it identically.
 */
fun releaseCertMarker(pin: String): String {
 var hash = "cbf29ce484222325".toULong(16)
 val prime = "100000001b3".toULong(16)
 for (byte in pin.lowercase().toByteArray(Charsets.US_ASCII)) {
  hash = (hash xor byte.toUByte().toULong()) * prime
 }
 return hash.toString(16).padStart(16, '0')
}

plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
 id("org.jetbrains.kotlin.kapt")
}
android {
 namespace = "cn.edu.sycu.schedule"
 compileSdk = 36
 ndkVersion = "27.2.12479018"
 defaultConfig { applicationId = "cn.edu.sycu.schedule"; minSdk = 26; targetSdk = 36; versionCode = providers.environmentVariable("ZHIXU_VERSION_CODE").orElse("2001").get().toInt(); versionName = providers.environmentVariable("ZHIXU_VERSION_NAME").orElse("0.2.0").get(); testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; buildConfigField("String", "RELEASE_CERT_SHA256", "\"\"") }
 defaultConfig {
  listOf("INITIALIZE", "AUTH_STATUS", "LOGIN", "SYNC_TIMETABLE", "LOGOUT", "ERR_AUTH", "ERR_OPERATION").forEachIndexed { index, name ->
   val value = providers.environmentVariable("ZHIXU_$name").orElse((index + 1).toString()).get().toInt()
   buildConfigField("int", name, value.toString())
  }
 }
 buildFeatures { compose = true; buildConfig = true }
 testOptions.unitTests.isIncludeAndroidResources = true
 buildTypes {
  getByName("debug") { isMinifyEnabled = false }
  getByName("release") {
   val cert = providers.environmentVariable("ZHIXU_RELEASE_CERT_SHA256").orElse("").get()
   buildConfigField("String", "RELEASE_CERT_SHA256", "\"$cert\"")
   isMinifyEnabled = true; isShrinkResources = true
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
  }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 packaging { jniLibs.useLegacyPackaging = false; resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.10.01"))
 implementation("androidx.activity:activity-compose:1.11.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui-tooling-preview")
 debugImplementation("androidx.compose.ui:ui-tooling")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
 implementation("androidx.room:room-runtime:2.8.3")
 implementation("androidx.room:room-ktx:2.8.3")
 kapt("androidx.room:room-compiler:2.8.3")
 implementation("androidx.core:core-ktx:1.17.0")
 implementation("io.noties.markwon:core:4.6.2")
 implementation("io.noties.markwon:ext-strikethrough:4.6.2")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 testImplementation("org.robolectric:robolectric:4.17")
 testImplementation("androidx.compose.ui:ui-test-junit4")
 androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
 androidTestImplementation("androidx.test:runner:1.7.0")
 androidTestImplementation("androidx.test.ext:junit:1.3.0")
 androidTestImplementation("androidx.test:rules:1.7.0")
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.configureEach {
 if (name == "preReleaseBuild") doFirst {
  listOf("INITIALIZE", "AUTH_STATUS", "LOGIN", "SYNC_TIMETABLE", "LOGOUT", "ERR_AUTH", "ERR_OPERATION").forEach { require(providers.environmentVariable("ZHIXU_$it").isPresent) { "Release requires paired build profile" } }
  require(providers.environmentVariable("ZHIXU_RELEASE_CERT_SHA256").orNull?.matches(Regex("[0-9a-fA-F]{64}")) == true) {
   "Release requires a pinned signing certificate digest"
  }
 }
 // Native Core is built outside this project. Refuse to package missing or mismatched
 // artifacts instead of producing an APK that exits silently on first Core call.
 if (name == "preBuild") doFirst {
  val pin = providers.environmentVariable("ZHIXU_RELEASE_CERT_SHA256").orNull?.lowercase()
  require(pin != null && pin.matches(Regex("[0-9a-f]{64}"))) {
   "Build requires ZHIXU_RELEASE_CERT_SHA256 (release certificate SHA-256) together with native libraries built for that certificate; refusing to package an unusable APK."
  }
  val marker = releaseCertMarker(pin)
  nativeAbis.forEach { abi ->
   val library = file("src/main/jniLibs/$abi/libsycu_core.so")
   require(library.isFile) {
    "Missing native library src/main/jniLibs/$abi/libsycu_core.so; build the Native Core for every ABI before assembling instead of packaging an APK without it."
   }
   require(library.readBytes().toString(Charsets.ISO_8859_1).contains(marker)) {
    "Native library $abi was not built for ZHIXU_RELEASE_CERT_SHA256=$pin; rebuild native artifacts before assembling, because a mismatched pin exits at run time."
   }
  }
 }
}

tasks.register("collectNotices") {
 doLast {
  val out = file("src/main/assets/licenses/android")
  out.mkdirs()
  val rows = mutableListOf("# Android dependencies", "")
  configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
   val id = artifact.moduleVersion.id
   rows += "${id.group}:${id.name}:${id.version}"
   if (artifact.file.extension in listOf("jar", "aar")) ZipFile(artifact.file).use { zip ->
    zip.entries().asSequence().filter { !it.isDirectory && (it.name.contains("LICENSE", true) || it.name.contains("NOTICE", true)) }.forEachIndexed { index, entry ->
     out.resolve("${id.group}-${id.name}-${id.version}-$index.txt").writeBytes(zip.getInputStream(entry).readBytes())
    }
   }
  }
  out.resolve("DEPENDENCIES.md").writeText(rows.joinToString("\n"))
 }
}

val projectLicenseAssets = layout.buildDirectory.dir("generated/projectLicenseAssets")
val copyProjectLicense by tasks.registering(Copy::class) {
 from(rootProject.file("LICENSE"))
 into(projectLicenseAssets.map { it.dir("licenses") })
 rename { "PROJECT-LICENSE.txt" }
}
android.sourceSets.getByName("main").assets.srcDir(projectLicenseAssets)
tasks.named("preBuild").configure { dependsOn(copyProjectLicense) }
