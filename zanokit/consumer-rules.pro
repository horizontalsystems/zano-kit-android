-keep class io.horizontalsystems.zanokit.** { *; }

# BouncyCastle — used by Bip39Derivation for secp256k1 key derivation
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# JNI — class and method names must survive R8 so the native bridge resolves correctly
-keep class io.horizontalsystems.zanokit.ZanoNative { *; }
