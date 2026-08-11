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
        versionCode = 1
        versionName = "1.0"

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

            optimization {
                enable = false
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