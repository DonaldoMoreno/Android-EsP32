# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools proguard/proguard-android.txt file.

# Keep USB serial driver classes
-keep class com.hoho.android.usbserial.** { *; }

# Keep MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }

# Keep model classes used by the ViewModel
-keep class com.thermocirculator.model.** { *; }
