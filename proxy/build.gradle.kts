import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Tar

plugins {
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("com.example.wolproxy.Main")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

val embeddedLinuxInstaller = layout.buildDirectory.file("generated/linux/start-linux.sh")
val linuxSourceDir = rootProject.file("linux")

val generateEmbeddedLinuxInstaller = tasks.register("generateEmbeddedLinuxInstaller") {
    inputs.file(linuxSourceDir.resolve("start-linux.sh"))
    inputs.file(linuxSourceDir.resolve("linux-installer-lib.sh"))
    outputs.file(embeddedLinuxInstaller)
    doLast {
        val start = linuxSourceDir.resolve("start-linux.sh").readText()
        val library = linuxSourceDir.resolve("linux-installer-lib.sh").readText()
        embeddedLinuxInstaller.get().asFile.apply {
            parentFile.mkdirs()
            writeText(start + "\n" + libraryBlock(library))
            setExecutable(true, false)
        }
    }
}

fun libraryBlock(library: String): String = buildString {
    append("# Embedded installer parsing helpers; do not edit this generated section.\n")
    append("__WOL_EMBEDDED_LIB_BEGIN__\n")
    append(library)
    if (!library.endsWith("\n")) append('\n')
    append("__WOL_EMBEDDED_LIB_END__\n")
}

distributions {
    main {
        contents {
            exclude("bin/wol-proxy.bat")
            from("config.example.yml")
            from("README.md")
            from(generateEmbeddedLinuxInstaller) {
                filePermissions {
                    unix("rwxr-xr-x")
                }
            }
            from(linuxSourceDir.resolve("wol")) {
                filePermissions {
                    unix("rwxr-xr-x")
                }
            }
        }
    }
}

fun org.gradle.api.file.FileCopyDetails.makeExecutable() {
    permissions {
        unix("rwxr-xr-x")
    }
}

tasks.named<Sync>("installDist") {
    exclude("bin/wol-proxy.bat")
    eachFile {
        if (relativePath.pathString == "bin/wol-proxy" ||
            relativePath.pathString == "start-linux.sh" ||
            relativePath.pathString == "wol"
        ) {
            makeExecutable()
        }
    }
}

tasks.withType<Tar>().configureEach {
    eachFile {
        if (relativePath.pathString.endsWith("/bin/wol-proxy.bat") ||
            relativePath.pathString == "bin/wol-proxy.bat"
        ) {
            exclude()
        } else if (relativePath.pathString.endsWith("/bin/wol-proxy") ||
            relativePath.pathString.endsWith("/start-linux.sh") ||
            relativePath.pathString.endsWith("/wol")
        ) {
            makeExecutable()
        }
    }
}

tasks.register<Exec>("testLinuxInstaller") {
    group = "verification"
    description = "Runs rootless unit tests for Linux installer parsing helpers."
    commandLine("sh", linuxSourceDir.resolve("tests/test-linux-installer.sh"))
}
