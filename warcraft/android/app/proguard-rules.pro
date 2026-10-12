# commons-compress refers to optional codecs (zstd, brotli, ...) the app doesn't ship.
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
# jsoup's null annotations are only for its own build.
-dontwarn org.jspecify.annotations.**

# Winlator: its native code calls into its Java classes by name, and it finds some by reflection.
-dontobfuscate
-keep class com.winlator.** { *; }
-dontwarn com.winlator.**
# zstd-jni (Winlator's archives): its native code uses its classes' fields and methods by name.
-keep class com.github.luben.zstd.** { *; }
