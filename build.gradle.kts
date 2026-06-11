plugins {
    java
    application
    id("com.gradleup.shadow") version "9.4.2"
}

group = "com.jposbox"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.anastaciocintra:escpos-coffee:4.1.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("com.jposbox.Main")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "jPosBox",
            "Implementation-Version" to project.version
        )
    }
}

tasks.shadowJar {
    archiveBaseName.set("jPosBox")
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes(
            "Implementation-Title" to "jPosBox",
            "Implementation-Version" to project.version
        )
    }
}

// Builds a native installer/app-image for the current OS using jpackage.
// Run on each target OS separately (mac -> dmg, windows -> msi/exe, linux -> deb/rpm).
tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Build a native installer with jpackage for the current OS"
    dependsOn(tasks.shadowJar)

    val osName = System.getProperty("os.name").lowercase()
    val type = when {
        osName.contains("mac") -> "dmg"
        osName.contains("win") -> "msi"
        else -> "deb"
    }

    val outputDir = layout.buildDirectory.dir("jpackage").get().asFile
    val inputDir = layout.buildDirectory.dir("libs").get().asFile
    val jarName = "jPosBox-${project.version}-all.jar"

    doFirst {
        outputDir.mkdirs()
    }

    val iconsDir = layout.projectDirectory.dir("src/main/resources/icons")
    val iconFile = when {
        osName.contains("mac") -> iconsDir.file("icon.icns")
        osName.contains("win") -> iconsDir.file("icon.ico")
        else -> iconsDir.file("icon.png")
    }

    val args = mutableListOf(
        "jpackage",
        "--type", type,
        "--input", inputDir.absolutePath,
        "--dest", outputDir.absolutePath,
        "--name", "jPosBox",
        "--main-jar", jarName,
        "--main-class", "com.jposbox.Main",
        "--app-version", project.version.toString(),
        "--vendor", "jPosBox",
        "--icon", iconFile.asFile.absolutePath,
        // jlink's default module detection misses reflection-based usages
        // (sqlite-jdbc, BouncyCastle, java.net.http), causing
        // "Failed to launch JVM" on the packaged app. Bundle everything.
        "--add-modules", "ALL-MODULE-PATH"
    )

    if (osName.contains("win")) {
        args += listOf("--win-shortcut", "--win-menu", "--win-dir-chooser")
    }

    if (osName.contains("mac")) {
        args += listOf("--mac-package-identifier", "com.jposbox.app")
        if (System.getenv("MAC_SIGN") == "true") {
            val identity = System.getenv("MAC_SIGNING_IDENTITY")
                ?: throw GradleException("MAC_SIGN=true but MAC_SIGNING_IDENTITY not set")
            args += listOf("--mac-sign", "--mac-signing-key-user-name", identity)
        }
    }

    commandLine(args)
}
