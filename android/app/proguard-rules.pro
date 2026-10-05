# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.maxicon.heard.**$$serializer { *; }
-keepclassmembers class com.maxicon.heard.** {
    *** Companion;
}
-keepclasseswithmembers class com.maxicon.heard.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }

# NanoHTTPD / NanoWSD
-keep class fi.iki.elonen.** { *; }

# Kotlin reflection (used by serialization)
-keep class kotlin.Metadata { *; }
