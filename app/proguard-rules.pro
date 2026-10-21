# keep defaults
# App code is kept whole (receivers, services, reflection-free but safest for a sideloaded app);
# R8 still shrinks and optimizes the libraries (Compose, Coil, coroutines), which is where the size and speed are.
-keep class com.heimish.messages.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
