plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.pulse.observability"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.pulse.observability"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        disable += "UnusedResources"
        // External freshness advisories: real upgrades are deliberate toolchain
        // changes, not per-commit fixes. This build pins the SDK/emulator pair
        // (platform 36) and AGP/Kotlin that the verification matrix runs on.
        // NotShrinkingResources, dependency-locator and code-level checks stay
        // enforced as errors.
        disable += setOf("OldTargetApi", "NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

configurations.configureEach {
    // BOM 2026.11.01 selects SDK 37+ artifacts. Preserve the requested BOM but resolve its
    // SDK-36-compatible trains consistently, including transitive and androidTest variants.
    // This is an intentional compatibility override, not a suppression of AAR metadata checks.
    resolutionStrategy.eachDependency {
        val version = when {
            requested.group in setOf("androidx.compose.ui", "androidx.compose.foundation", "androidx.compose.animation", "androidx.compose.runtime") -> "1.10.0"
            requested.group == "androidx.compose.material3" -> "1.4.0"
            requested.group == "androidx.compose.material" -> "1.7.8"
            requested.group == "androidx.lifecycle" -> "2.10.0"
            requested.group == "androidx.core" && requested.name in setOf<String>("core", "core-ktx", "core-android") -> "1.18.0"
            else -> null
        }
        if (version != null) useVersion(version)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bouncycastle.bcpkix)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.bouncycastle.bcpkix)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
}
