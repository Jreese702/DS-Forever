// DS FOREVER INSIDE THE LAUNCHER (the private fork, phase 2, 2026-10-08): the companion's own views, built from the DS Forever app's
// source (every file but its MainActivity, as its homepreview tool does), plus this module's glue (fork/DsForeverScreen.kt) that shows
// Home on the bottom screen as a Presentation of the launcher's game screen. No root in this process (RootPolicy).
//   -PdsAppDir=<path to the companion's src/main> (default: the included companion source)
plugins {
    id("com.android.library")
    alias(libs.plugins.jetbrains.kotlin.android)
}

val dsAppDir = (findProperty("dsAppDir") as String?) ?: "${rootDir}/companion/app/src/main"
require(file("$dsAppDir/java/com/jrees/wowcompanion/ArtHarvest.kt").isFile) {
    "Companion sources are missing: expected companion/app/src/main, or set -PdsAppDir explicitly"
}
val sharedDir = layout.buildDirectory.dir("shared")
val syncShared = tasks.register<Sync>("syncShared") {
    from("$dsAppDir/java/com/jrees/wowcompanion") { exclude("MainActivity.kt") }
    into(sharedDir.map { it.dir("com/jrees/wowcompanion") })
}

android {
    namespace = "com.jrees.wowcompanion"
    compileSdk = 36
    defaultConfig {
        minSdk = 29
        buildConfigField("boolean", "SHARE", "false")
        // the problem report's receiver (tools/report-worker/README.md): "" = save + the browser's pre-filled issue, as before.
        // No secret: the key ships in the APK and only keeps casual traffic out of the receiver
        buildConfigField("String", "REPORT_ENDPOINT", "\"${(findProperty("dsfReportEndpoint") as String?).orEmpty()}\"")
        buildConfigField("String", "REPORT_KEY", "\"${(findProperty("dsfReportKey") as String?).orEmpty()}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].apply {
        java.srcDirs(sharedDir.get().asFile, "src/main/java")
        assets.srcDirs("$dsAppDir/assets")
        // the art harvest's file reader (CascLib over JNI, built by the app's tools/harvest/build_native.sh; not in git)
        jniLibs.srcDirs("$dsAppDir/jniLibs")
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

tasks.named("preBuild") { dependsOn(syncShared) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // off-device tests of the companion's pure parts (launch support: WowProduct, GamePaths)
    testImplementation(libs.junit)
    // the real org.json for those tests (android.jar's is a stub that throws "not mocked"): the quests / pmenu documents' models
    testImplementation("org.json:json:20231013")
}
