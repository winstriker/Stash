plugins {
    id("stash.android.feature")
}
android {
    namespace = "com.stash.feature.home"

    testOptions {
        unitTests {
            // Return Kotlin defaults from stubbed Android SDK methods so
            // android.util.Log calls in production code don't throw in JVM tests.
            isReturnDefaultValues = true
        }
    }
}
dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    // For FileOrganizer.getTotalStorageBytes / getLosslessStorageBytes —
    // disk-truth Storage stats on the Home sync card (bypassing the
    // unreliable DB `file_size_bytes` SUM for legacy libraries).
    implementation(project(":data:download"))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation("junit:junit:4.13.2")
}
