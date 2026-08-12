plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
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
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // BuildConfig.DEBUG is what keeps the debug encounter trigger out of a
    // release build. AGP stopped generating BuildConfig by default in 8.0.
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // Signed with the debug key on purpose. An unsigned APK cannot be
            // installed on anything, so a release build would be untestable and
            // unshareable without it. This must be swapped for a real keystore
            // before a Play upload - the debug key is shared by every Android
            // install on the machine and identifies nobody.
            signingConfig = signingConfigs.getByName("debug")

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
}