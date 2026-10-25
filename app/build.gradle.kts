import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val unsignedRelease = providers.gradleProperty("astraUnsigned").orNull == "true"
val signingPropertiesFile = providers.gradleProperty("astraSigningProperties").orNull
    ?.let { rootProject.file(it) }
    ?: File(System.getProperty("user.home"), ".android/astra-sec/signing.properties")
val environmentSigning = providers.environmentVariable("ASTRA_SIGNING_STORE_FILE").orNull
    ?.takeIf { it.isNotBlank() } != null
val signingProperties = Properties().apply {
    if (!unsignedRelease && !environmentSigning && signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}
fun signingValue(name: String): String? = (if (environmentSigning) {
    providers.environmentVariable(name).orNull
} else signingProperties.getProperty(name))?.takeIf { it.isNotBlank() }
val signingStore = if (unsignedRelease) null else signingValue("ASTRA_SIGNING_STORE_FILE")
val signingKeyFile = signingStore?.let {
    val path = File(it)
    when {
        path.isAbsolute -> path
        environmentSigning -> rootProject.file(it)
        else -> signingPropertiesFile.parentFile.resolve(it)
    }
}

android {
    namespace = "org.astrasec.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.astrasec.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseSigning = if (signingKeyFile != null) {
        signingConfigs.create("astraRelease") {
            storeFile = signingKeyFile
            storeType = "PKCS12"
            fun requiredValue(name: String) = signingValue(name)
                ?: error("$name is required for release signing")
            storePassword = requiredValue("ASTRA_SIGNING_STORE_PASSWORD")
            keyAlias = requiredValue("ASTRA_SIGNING_KEY_ALIAS")
            keyPassword = requiredValue("ASTRA_SIGNING_KEY_PASSWORD")
        }
    } else null

    buildTypes {
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        if (!unsignedRelease) {
            check(signingKeyFile != null) {
                "Release signing is missing. Configure ~/.android/astra-sec/signing.properties " +
                    "or ASTRA_SIGNING_* environment variables; use -PastraUnsigned=true for unsigned builds."
            }
            check(signingKeyFile.isFile) { "The configured release keystore does not exist." }
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(requireReleaseSigning)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.1.0")

    testImplementation("junit:junit:4.13.2")
}

