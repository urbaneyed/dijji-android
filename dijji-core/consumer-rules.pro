# Dijji SDK — consumer ProGuard rules. Applied automatically when the host
# app enables minification, so Kaabil devs don't have to copy anything.

# Keep public API (devs call these by name)
-keep class com.dijji.sdk.Dijji { *; }
-keep class com.dijji.sdk.DijjiConfig { *; }
-keep class com.dijji.sdk.DijjiInstaller { *; }

# Moshi + Kotlin reflection for JSON serialization
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.JsonClass <init>(...);
}

# OkHttp — its reflection is benign but R8 warns without this
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
