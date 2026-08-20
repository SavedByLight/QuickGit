import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.quickgit.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.quickgit.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0"
    }

    signingConfigs {
        create("release") {
            // Prefer env vars (CI). Fall back to local.properties for local builds.
            val propsFile = rootProject.file("local.properties")
            val props = Properties()
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }
            fun prop(name: String): String? =
                System.getenv(name) ?: props.getProperty(name)

            val store = prop("SIGNING_STORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = prop("SIGNING_STORE_PASSWORD")
                keyAlias = prop("SIGNING_KEY_ALIAS")
                keyPassword = prop("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Backports InputStream#readNBytes and other JDK 9+ java.io/java.util APIs to
        // devices below API 33 (e.g. Amazon Fire tablets, which top out around API 30) —
        // both JGit's SilentFileInputStream and RepoManager.readTextFile rely on it.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                // Both org.eclipse.jgit and org.eclipse.jgit.ssh.apache ship this
                "OSGI-INF/l10n/plugin.properties",
                // org.eclipse.jgit, .ssh.apache, and .gpg.bc all ship an identical
                // root-level copy too (OSGi bundle l10n default) — same content,
                // just at a different path, so it hits mergeReleaseJavaResource too.
                "plugin.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Stay on BOM material3 — 1.5.x alpha requires AGP 8.6+/compileSdk 35–37.
    // M3E-style theming is implemented in Theme.kt (dynamic color, shapes, type).
    implementation("androidx.compose.material3:material3")
    // Window size classes for tablets / Chromebooks / multi-window (Material3 adaptive)
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    // Avatar images (GitHub user/profile pictures)
    implementation("io.coil-kt:coil-compose:2.6.0")    // Pull-to-refresh (androidx.compose.material.pullrefresh) still lives in the M2 "material"
    // artifact even for Material3 apps; version is resolved by the compose-bom platform above.
    implementation("androidx.compose.material:material")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JGit core + pure-Java (Apache MINA) SSH transport — works on Android, no native libs
    //
    // Pinned to the last patched 5.x release (not 6.x+) because JGit 6.0 raised its
    // baseline to Java 11 and started calling InputStream#readNBytes internally
    // (SilentFileInputStream / IO.readFully) — a method Android didn't add to its
    // platform InputStream until API 33. Below that (e.g. Amazon Fire tablets, which
    // top out around API 30 even on current models), any repo config load crashes with
    // NoSuchMethodError. It's not something app code or core library desugaring can
    // patch — the call is inside JGit's own compiled bytecode. 5.13.3 backports the
    // CVE-2023-4759 symlink RCE fix, so it's still a maintained, secure release.
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
    // org.eclipse.jgit.ssh.apache's POM pulls in BOTH the individual Apache MINA sshd
    // jars (sshd-core/sshd-common/sshd-sftp) AND sshd-osgi, which repackages those same
    // classes into one bundle for OSGi environments. On a plain classpath (not OSGi) that's
    // a straight duplicate — Android's checkReleaseDuplicateClasses task fails on classes
    // like org.apache.sshd.server.session.ServerSession existing in both jars. We don't
    // need the OSGi bundle here, so drop it and keep the individual sshd artifacts.
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:5.13.3.202401111512-r") {
        exclude(group = "org.apache.sshd", module = "sshd-osgi")
    }
    // OpenPGP commit signing (Bouncy Castle backend)
    implementation("org.eclipse.jgit:org.eclipse.jgit.gpg.bc:5.13.3.202401111512-r")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
