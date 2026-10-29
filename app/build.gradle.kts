import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Signing.
// The release keystore is NEVER committed. CI injects it from repository
// secrets, or you create one on-device (see docs/BUILD_APK.md). With no
// keystore configured the release variant is still assembled but left
// unsigned; the debug variant is always installable.
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile")?.isNotBlank() == true

val varunAbis = (project.findProperty("VARUN_ABIS") as String?)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a")

val llamaCppSrc = (project.findProperty("VARUN_LLAMA_CPP_SRC") as String?) ?: ""

android {
    namespace = "dev.varun.studio"
    compileSdk = 35
    ndkVersion = (project.findProperty("VARUN_NDK_VERSION") as String?) ?: "27.2.12479018"

    defaultConfig {
        applicationId = "dev.varun.studio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk { abiFilters.addAll(varunAbis) }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DVARUN_LLAMA_CPP_SRC=$llamaCppSrc",
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = (project.findProperty("VARUN_CMAKE_VERSION") as String?) ?: "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Kept OFF by default: R8 + JNI + llama.cpp works, but shrinking the
            // Kotlin layer risks stripping reflection-touched Compose code and
            // complicating a first real-device install. Flip on once the app is
            // confirmed working on your phone.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
            )
        }
        jniLibs { useLegacyPackaging = false }
    }

    // Kotlin sources live under src/<name>/kotlin. The Kotlin Android plugin
    // registers those directories by default, but they are declared explicitly
    // so the unit-test source set is still found if that default ever changes.
    sourceSets {
        getByName("main").java.srcDirs("src/main/java", "src/main/kotlin")
        getByName("test").java.srcDirs("src/test/java", "src/test/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/java", "src/androidTest/kotlin")
    }

    // Native builds are slow; keep them out of unit-test compilation.
    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    debugImplementation(libs.androidx.compose.ui.tooling)
}
