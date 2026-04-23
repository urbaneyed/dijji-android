plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.dijji.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        // Flow SDK version + default endpoint into BuildConfig so neither has
        // to be duplicated in code. Bumped via gradle.properties at release.
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
        buildConfigField(
            "String",
            "DEFAULT_ENDPOINT",
            "\"${providers.gradleProperty("dijji.endpoint").orElse("https://dijji.com").get()}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Public API — we want strict nullability guarantees leaking to consumers
        freeCompilerArgs += listOf(
            "-Xexplicit-api=strict",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime)
    implementation(libs.startup.runtime)
    implementation(libs.okhttp)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.install.referrer)

    // Room for offline event queue. kapt annotation processor for schema gen.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
}
