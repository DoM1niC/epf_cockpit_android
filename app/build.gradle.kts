import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 bringt Kotlin-Support eingebaut mit — das separate
    // org.jetbrains.kotlin.android-Plugin darf nicht mehr angewendet werden
    // (siehe https://kotl.in/gradle/agp-built-in-kotlin). Compiler-Plugins
    // (Compose, Serialization, KSP) bleiben.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Optionales Release-Keystore. Quellen in Reihenfolge:
//   1. `local.properties` mit den RELEASE_*-Variablen (entwickler-spezifisch,
//      nicht committet, gleiche Datei wie sdk.dir) — CI nutzt diesen Pfad.
//   2. `keystore.properties` (alternativer Standort, ebenfalls gitignored).
// Fehlt beides, wird der Release-Build mit dem Debug-Key signiert.
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) localPropsFile.inputStream().use { load(it) }
}
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

data class ResolvedSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

val resolvedSigning: ResolvedSigning? = run {
    val ls = localProps.getProperty("RELEASE_STORE_FILE")
    val lp = localProps.getProperty("RELEASE_STORE_PASSWORD")
    val la = localProps.getProperty("RELEASE_KEY_ALIAS")
    val lk = localProps.getProperty("RELEASE_KEY_PASSWORD")
    if (!ls.isNullOrBlank() && !lp.isNullOrBlank() && !la.isNullOrBlank() && !lk.isNullOrBlank()) {
        val f = File(ls).let { if (it.isAbsolute) it else rootProject.file(ls) }
        if (f.exists()) return@run ResolvedSigning(f, lp, la, lk)
        else logger.warn("RELEASE_STORE_FILE in local.properties verweist auf $f, existiert aber nicht")
    }
    val ks = keystoreProps.getProperty("storeFile")
    val ksp = keystoreProps.getProperty("storePassword")
    val ka = keystoreProps.getProperty("keyAlias")
    val kk = keystoreProps.getProperty("keyPassword")
    if (!ks.isNullOrBlank() && !ksp.isNullOrBlank() && !ka.isNullOrBlank() && !kk.isNullOrBlank()) {
        val f = rootProject.file(ks)
        if (f.exists()) return@run ResolvedSigning(f, ksp, ka, kk)
    }
    null
}
val hasReleaseKeystore = resolvedSigning != null

android {
    namespace = "eu.dns3d.epfcockpit"
    // compileSdk 37 nötig: die gebumpten Deps (compose 1.12, core-ktx 1.19.0,
    // navigation-compose 2.10) verlangen Compile gegen API 37 (minCompileSdk).
    // targetSdk bleibt 36 (kein Opt-in in neues Runtime-Verhalten).
    compileSdk = 37

    defaultConfig {
        applicationId = "eu.dns3d.epfcockpit"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        resolvedSigning?.let { rs ->
            create("release") {
                storeFile = rs.storeFile
                storePassword = rs.storePassword
                keyAlias = rs.keyAlias
                keyPassword = rs.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AGP-9-Built-in-Kotlin: Compiler-Optionen über die kotlin{}-Extension statt
// des entfernten android.kotlinOptions-Blocks.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.errorprone.annotations)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)
    implementation(libs.nordic.scanner)

    implementation(libs.accompanist.permissions)

    // Telemetry-Streaming an Node-Dashboard (WebSocket-Client + JSON)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
