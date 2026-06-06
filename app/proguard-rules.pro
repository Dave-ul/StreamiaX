# slf4j - referenced by ktor-client-logging, not needed on Android
-dontwarn org.slf4j.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Ktor
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.streamiax.**$$serializer { *; }
-keepclassmembers class com.streamiax.** { *** Companion; }
-keepclasseswithmembers class com.streamiax.** { kotlinx.serialization.KSerializer serializer(...); }

# Hilt
-dontwarn dagger.hilt.**
