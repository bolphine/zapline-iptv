import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Version shown in the app and on the Windows package; kept in step with the phone app.
val appVersion = "2.2.0"

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    // Android has these built in; the desktop runtime needs its own.
    implementation("org.json:json:20240303")
    implementation("net.sf.kxml:kxml2:2.3.0")
    // libmpv (the video engine) is loaded through JNA.
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation(compose.desktop.uiTestJUnit4)
}

val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/zapline/iptv/desktop/AppVersion.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package com.zapline.iptv.desktop\n\nconst val APP_VERSION = \"$appVersion\"\n")
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateVersion)

compose.desktop {
    application {
        mainClass = "com.zapline.iptv.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g", "-Dsun.java2d.d3d=false", "-Dskiko.renderApi=DIRECT3D")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Zapline"
            packageVersion = appVersion
            description = "IPTV player for ultrawide monitors"
            vendor = "Zapline"
            includeAllModules = true
            // libmpv-2.dll goes in resources/windows (fetched by the Windows build) and ships next to Zapline.exe.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                menuGroup = "Zapline"
                shortcut = true
                dirChooser = true
                upgradeUuid = "6c1d2a3e-9f7b-4b8e-a1c5-3d2f8e7b9a10"
                iconFile.set(project.file("icon/zapline.ico"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    // No native video window in tests; the picture renders as a placeholder.
    systemProperty("zapline.noVideo", "true")
    systemProperty("java.awt.headless", "true")
    // CI points this at the libmpv it downloaded.
    System.getenv("ZAPLINE_MPV_DIR")?.let { systemProperty("jna.library.path", it) }
}
