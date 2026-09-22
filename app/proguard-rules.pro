# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# https://f-droid.org/en/docs/Reproducible_Builds/
-keep class kotlinx.coroutines.CoroutineExceptionHandler
-keep class kotlinx.coroutines.internal.MainDispatcherFactory

# Xiaomi PenEngine (optional AAR in app/libs) — keep so reflection bridge keeps working.
# https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1883
-dontwarn  com.miui.penengine.**
-keep class com.miui.penengine.ocr.** {*;}
-keep class com.miui.penengine.estimate.** {*;}
-keep class com.miui.penengine.onestroke.MiuiOneStroke {*;}
-keep class com.miui.penengine.hover.MiuiHoverPreview {*;}
-keep class com.miui.penengine.posture.MiuiStylusPosture {*;}
-keep class com.miui.penengine.colorpick.** {*;}
-keep class com.miui.penengine.touchfilm.** {*;}
-keep class com.miui.penengine.auth.** {*;}
