plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
}

// Single published group/version for all modules. Bump `dijji.version` in
// gradle.properties for each release; CI picks it up and feeds it into the
// SDK so /t/app/* receives the accurate X-Dijji-Sdk-Version header.
allprojects {
    group = "com.dijji"
    version = providers.gradleProperty("dijji.version").orElse("1.0.0-SNAPSHOT").get()
}
