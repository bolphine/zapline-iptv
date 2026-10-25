plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Shared by the Android app and the Windows app: playlists, tagging, TV guide, catch-up, channel checks,
// provider sign-in, sports data and settings. Plain Kotlin, no Android.
// Bytecode for Java 17 (Android and the bundled desktop runtime), built with whatever JDK is installed.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.google.code.gson:gson:2.11.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Built into Android; each desktop/test runtime brings its own copy.
    compileOnly("org.json:json:20240303")
    compileOnly("net.sf.kxml:kxml2:2.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
