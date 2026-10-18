plugins { kotlin("jvm") version "2.1.21" }
// the SDK's android.jar (compile only: Robolectric brings the real one); the app's classes as a jar (run.sh builds it)
val sdk = System.getenv("ANDROID_HOME") ?: (System.getenv("LOCALAPPDATA") + "/Android/Sdk")
val androidJar = System.getenv("ANDROID_JAR") ?: "$sdk/platforms/android-34/android.jar"
val work = System.getenv("SAFEPATH_WORK") ?: "E:/safepath-work"
dependencies {
    testCompileOnly(files(androidJar))
    testRuntimeOnly(files(androidJar))
    testImplementation(files(layout.buildDirectory.file("app.jar")))     // this checkout's own classes (run.sh builds it)
    testImplementation(files("$work/monitor-1.7.2.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
kotlin { jvmToolchain(17) }
tasks.test {
    maxHeapSize = "8g"
    systemProperty("robolectric.offline", "false")
    systemProperty("out.dir", System.getenv("SAFEPATH_OUT") ?: "$work/out")      // an agent's own folder when several share the work folder
    testLogging { showStandardStreams = true; events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
configurations.all { resolutionStrategy.force("com.google.guava:guava:33.0.0-jre") }
