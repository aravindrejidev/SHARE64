# The native engine calls TransferEngine.onNativeEvent(...) by name and Kotlin binds the natives by name.
-keep class com.share64.core.TransferEngine { *; }
-keepclasseswithmembernames class * { native <methods>; }
