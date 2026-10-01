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
# WebRTC (JNI)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# MediaPipe's AAR bundles AutoValue's shaded JavaPoet, which references the javax.lang.model
# compiler API that does not exist on Android. The code path is never executed at runtime;
# R8 only needs to be told not to fail the build over the missing classes.
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
