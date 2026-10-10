# PureTV for Twitch — TV app ProGuard/R8 rules.
# Mirrors app-android's needs (Media3/ExoPlayer, Room, Koin, kotlinx.serialization)
# plus Compose for TV / Leanback specifics.

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Compose for TV / Leanback
-keep class androidx.tv.** { *; }
-keep class androidx.leanback.** { *; }

# Room (generated DAO/entity implementations)
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Koin
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# kotlinx.serialization models (Twitch Helix/GQL DTOs in `core`)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class com.puretv.twitch.core.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.puretv.twitch.core.**$$serializer { *; }
-keepclassmembers class com.puretv.twitch.core.** {
    *** serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# SMBJ's default authenticator has optional Java SE Kerberos/GSSAPI support,
# and the bundled message bus filter can evaluate optional EL expressions.
# Android does not provide these APIs; the TV app uses NTLM/guest SMB auth.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**

# The SMB filesystem-space query constructs SMB2_QUERY_INFO through reflection
# because SMBJ's NIO FileStore API is not implemented.
-keep class com.hierynomus.mssmb2.messages.SMB2QueryInfoRequest { *; }
-keep class com.hierynomus.mssmb2.messages.SMB2QueryInfoRequest$SMB2QueryInfoType { *; }
-keep class com.hierynomus.mssmb2.messages.SMB2QueryInfoResponse { *; }
-keep class com.hierynomus.mssmb2.SMB2Packet { *; }

# Ktor's optional IntelliJ debugger detector references JVM management APIs
# that are absent on Android. They are only used by its desktop debug path.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Keep our own model/DTO classes (reflection-free, but names matter for serialization)
-keep class com.puretv.twitch.core.model.** { *; }
-keep class com.puretv.twitch.core.api.** { *; }
