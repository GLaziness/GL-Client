-dontobfuscate

-keep class mindustry.** { *; }
-keep class arc.** { *; }
-keep class net.jpountz.** { *; }
-keep class rhino.** { *; }
-keep class com.android.dex.** { *; }
-keep class com.android.dx.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

-dontwarn javax.naming.**

#-printusage out.txt

# Desktop only in GL Client: Kotlin scripts (!kt, guarded), the JVM restart (desktop only).
-dontwarn javax.script.**
-dontwarn java.lang.management.**

# Looked up by name through the JCA (Security providers).
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
