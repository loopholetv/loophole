import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing secrets live outside the repo (see README "Release signing"). Location: Gradle
// property `loophole.signing`, else env LOOPHOLE_SIGNING_PROPERTIES, else
// ~/.loophole-signing/keystore.properties. Without it, release builds are unsigned (CI-friendly).
val signingPropertiesFile: File = file(
    providers.gradleProperty("loophole.signing")
        .orElse(providers.environmentVariable("LOOPHOLE_SIGNING_PROPERTIES"))
        .getOrElse("${System.getProperty("user.home")}/.loophole-signing/keystore.properties")
        .replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home")),
)
val releaseSigning: Properties? = signingPropertiesFile.takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
if (releaseSigning == null) {
    logger.warn("WARNING: Loophole release signing properties not found at $signingPropertiesFile; release APKs will be UNSIGNED.")
}

android {
    namespace = "io.github.loopholetv"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.loopholetv"
        minSdk = 25
        targetSdk = 37
        versionCode = 4
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile").replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home")))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (releaseSigning != null) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release code (R8, non-debuggable) signed with the debug key, for on-device profiling only.
        // Installs over a debug build of the same app id and keeps its data (e.g. the debug seed).
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // Room's exported schemas, read from assets by MigrationTestHelper. Robolectric unit tests only
        // see the variant's merged assets (not the test source set's), so they go in debug; release is unaffected.
        getByName("debug").assets.directories.add("$projectDir/schemas")
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.moshi)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.kxml2)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
}
