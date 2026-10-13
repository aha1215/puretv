plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.puretv.twitch.tv.benchmark"
    compileSdk = 35
    targetProjectPath = ":app-tv"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("benchmark") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("benchmark")
        }
    }

    variantFilter {
        if (buildType.name == "debug") ignore = true
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // app-tv gained a "distribution" flavor dimension (full/play); the
        // benchmark harness measures the `full` sideload app by default.
        missingDimensionStrategy("distribution", "full")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.benchmark.macro)
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.test:core-ktx:1.6.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.ext:junit:1.2.1")
}
