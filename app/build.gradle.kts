import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.quickgit.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.quickgit.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
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
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:6.10.0.202406032230-r")
    // OpenPGP commit signing (Bouncy Castle backend)
    implementation("org.eclipse.jgit:org.eclipse.jgit.gpg.bc:6.10.0.202406032230-r")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
