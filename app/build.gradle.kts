plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.decisionoverlay.checker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.decisionoverlay.checker"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BINANCE_API_KEY", binanceConfig("BINANCE_API_KEY"))
        buildConfigField("String", "BINANCE_API_BASE_URL", binanceConfig("BINANCE_API_BASE_URL"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

fun binanceConfig(name: String): String {
    val fromEnv = System.getenv(name)?.trim().orEmpty()
    val raw = if (fromEnv.isNotEmpty()) {
        fromEnv
    } else {
        val file = rootProject.file("local.properties")
        if (!file.exists()) {
            ""
        } else {
            file.readLines()
                .firstOrNull { it.trim().startsWith("$name=") }
                ?.substringAfter("=")
                ?.trim()
                .orEmpty()
        }
    }
    val safe = raw.trim().trimEnd('/').filter { char ->
        char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' || char == ':' || char == '/'
    }
    return "\"$safe\""
}
