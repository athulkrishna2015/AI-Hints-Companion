# Keep Chaquopy/Python entry points reachable from the JNI bridge.
-keep class com.chaquo.python.** { *; }
-keep class dev.aihints.companion.python.** { *; }
-dontwarn org.nanohttpd.**
