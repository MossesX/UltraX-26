# MediaPipe Tasks (JNI + protobuf-lite)
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.ultrax26.recorder.**$$serializer { *; }
-keepclassmembers class com.ultrax26.recorder.** { *** Companion; }
-keepclasseswithmembers class com.ultrax26.recorder.** { kotlinx.serialization.KSerializer serializer(...); }
