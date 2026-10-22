# Libraries ship their own consumer rules (Media3, OkHttp, Coil).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Cast options provider is named only in the manifest meta-data.
-keep class com.zapline.iptv.player.CastOptionsProvider { *; }
