plugins { alias(libs.plugins.android.dynamic.feature) }
android {
    namespace = "com.androidharness.gpu"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildTypes { release { isMinifyEnabled = false } }
    for (name in listOf("debug", "release")) sourceSets.getByName(name).jniLibs.srcDir("../app/build/generated/gpuDelivery/$name/jniLibs")
}
dependencies { implementation(project(":app")) }
tasks.configureEach {
    when (name) {
        "mergeDebugJniLibFolders" -> dependsOn(":app:prepareGpuRuntimeDebug")
        "mergeReleaseJniLibFolders" -> dependsOn(":app:prepareGpuRuntimeRelease")
    }
}
