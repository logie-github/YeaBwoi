# LibretroDroid calls into its Java and Kotlin classes from native code, by name.
-keep class com.swordfish.libretrodroid.** { *; }

# pe7z's native code reports progress through SevenZip.Progress.onProgress, by name.
-keep interface com.logie.pe.hd.SevenZip$Progress { *; }
-keepclassmembers class * implements com.logie.pe.hd.SevenZip$Progress { boolean onProgress(long, long); }
