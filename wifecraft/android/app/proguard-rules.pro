# The renderer's JNI functions are found by name.
-keep class com.logie.wifecraft.engine.Native { *; }
# The dialogue model's JNI functions and the interface its native side calls back into.
-keep class com.logie.wifecraft.dialogue.NativeDialogue { *; }
-keep interface com.logie.wifecraft.dialogue.PieceSink { *; }
