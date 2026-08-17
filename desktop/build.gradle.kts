import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
        withJava()
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

                // JGit for desktop
                implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
                implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:5.13.3.202401111512-r") {
                    exclude(group = "org.apache.sshd", module = "sshd-osgi")
                }
                implementation("org.eclipse.jgit:org.eclipse.jgit.gpg.bc:5.13.3.202401111512-r")
            }
        }
    }
}

// Match Java compile target
tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

compose.desktop {
    application {
        mainClass = "com.quickgit.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "QuickGit"
            packageVersion = "1.0.0"
            description = "A GitHub Desktop-style git client for Linux, Windows and macOS"
            copyright = "© 2025 QuickGit"
            vendor = "QuickGit"

            linux {
                iconFile.set(project.file("src/jvmMain/resources/icon.png"))
                debMaintainer = "quickgit@example.com"
                menuGroup = "Development"
                appCategory = "Development"
            }

            windows {
                menuGroup = "QuickGit"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }

            macOS {
                bundleID = "com.quickgit.desktop"
            }
        }
    }
}
