# Keep Room entities/DAO for schema reflection used by generated code.
-keep class se.iteca.eneotaltilltext.data.** { *; }

# OkHttp / platform
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
