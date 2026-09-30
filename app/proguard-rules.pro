-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
# JNA binds this interface by reflection
-keep interface com.appsalad.recorder.audio.** extends com.sun.jna.Library { *; }
