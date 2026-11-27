#!/bin/bash
#!/bin/bash
# Compiles ALL the app's Kotlin with the plain Kotlin compiler (K2JVMCompiler from the Gradle cache) and the Compose compiler
# plugin, against the jars in ./libs (resolve_libs.py) and a Robolectric android-all jar (./android-all.jar; any recent
# org.robolectric:android-all from Maven Central): no Android SDK, no AGP. About 90 s. Then measure with method_sizes.py.
#   compile_app.sh ../../android/app/src/main/java OUT_DIR
# BuildConfig and R are stubbed (stubs/Gen.kt). The nullability flags make android-all's annotations behave like the SDK's android.jar.
# usage: compile.sh <srcroot (app/src/main/java)> <outdir>
G=~/.gradle/caches/modules-2/files-2.1
K=$(ls $G/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.20/*/kotlin-compiler-embeddable-2.2.20.jar)
STD=$(ls $G/org.jetbrains.kotlin/kotlin-stdlib/2.2.20/*/kotlin-stdlib-2.2.20.jar)
SR=$(ls $G/org.jetbrains.kotlin/kotlin-script-runtime/2.2.20/*/*.jar | grep -v sources)
RF=$(ls $G/org.jetbrains.kotlin/kotlin-reflect/1.6.10/*/kotlin-reflect-1.6.10.jar)
DM=$(ls $G/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.20/*/*.jar | grep -v sources)
AN=$(ls $G/org.jetbrains/annotations/13.0/*/annotations-13.0.jar)
CO=$(ls $G/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/*/*.jar | grep -v sources)
CP=$CO:$K:$STD:$SR:$RF:$DM:$AN
COMP=$(ls $G/org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.2.20/*/*.jar | grep -v sources)
W=$(dirname $(readlink -f $0))
LIBS=$(ls $W/libs/*.jar | tr '\n' ':')$W/android-all.jar
mkdir -p $2
exec java -Xmx3g -cp $CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Xjdk-release=17 \
  -classpath "$LIBS:$STD" -Xplugin=$COMP -d $2 -nowarn -Xnullability-annotations=@android.annotation.Nullable:ignore -Xnullability-annotations=@android.annotation.NonNull:ignore -Xnullability-annotations=@javax.annotation.Nullable:ignore $W/stubs/Gen.kt "$1" 2>&1
