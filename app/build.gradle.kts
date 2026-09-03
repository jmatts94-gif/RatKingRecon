import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

/**
 * The real signing key, if this machine has one.
 *
 * keystore.properties and the .jks it points at are both gitignored and must
 * stay that way: the file holds the password in plain text, and the key itself
 * is the app's identity on Play - an upload signed by a different key is
 * rejected as a different app, permanently. There is no recovery from losing
 * it, only a new listing.
 *
 * Absent on a fresh clone, which is deliberate. The build falls back to the
 * debug key rather than failing, so anyone can check the project out and build
 * it; only a machine holding the real key produces a publishable APK.
 */
val releaseKeystore: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

/**
 * Crashlytics needs values (app id, API key) that only exist once this file
 * has been downloaded from a real Firebase project console and dropped in -
 * there is nothing to generate it from locally. Gated the same way as
 * [releaseKeystore]: absent on a fresh clone is the normal case, and the
 * build should stay green rather than fail on a missing file nobody but the
 * project owner can produce.
 */
val hasFirebaseConfig = file("google-services.json").exists()

if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "io.github.jmatts94.ratkingrecon"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.jmatts94.ratkingrecon"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "0.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // BuildConfig.DEBUG is what keeps the debug encounter trigger out of a
    // release build. AGP stopped generating BuildConfig by default in 8.0.
    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // Left unconfigured when there is no keystore.properties. Nothing
            // references this config in that case - see the release build type.
            releaseKeystore?.let { props ->
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // So the About screen can be asked which build is installed.
            //
            // Both build types carry the same applicationId and, since release
            // is signed with the debug key too, the same certificate - which
            // means either APK installs straight over the other with nothing on
            // screen to say so. The debug-only triggers then appear to be
            // broken, when what has actually happened is that a release build
            // replaced the debug one.
            //
            // Deliberately not an applicationIdSuffix, which would let both sit
            // side by side but would give the debug build its own save, and a
            // player's collection appearing to vanish is a worse surprise than
            // the one this is fixing.
            versionNameSuffix = "-debug"
        }

        release {
            /*
             * The real key when this machine has one, the debug key otherwise.
             *
             * An unsigned APK cannot be installed on anything, so falling back
             * keeps a release build testable and shareable on a machine without
             * the keystore. What it does not do is make that build publishable:
             * the debug key ships with the SDK, is shared by every Android
             * install on the machine, and identifies nobody. Check which key an
             * APK actually carries before uploading it -
             *
             *   apksigner verify --print-certs <apk>
             *
             * "CN=Android Debug" means the fallback was used.
             */
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }

            // R8: shrink, optimise and obfuscate. Safe to turn on here because
            // nothing in the app reaches for a class by name - no reflection, no
            // reflective JSON (SaveTransfer builds org.json by hand), and Room
            // ships its own keep rules for the code KSP generates.
            //
            // Project rules live in src/main/keepRules; AGP combines everything
            // there and hands it to R8.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Room writes its schema JSON here; keep it in git so future migrations
// can be generated and verified against a known previous version.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.viewpager2)
    // Declared rather than taken transitively from viewpager2: the Binder grid
    // depends on it directly, so it should not be at the mercy of another
    // library's dependency graph.
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    if (hasFirebaseConfig) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.crashlytics)
    }
}