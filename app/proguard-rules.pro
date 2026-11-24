# VaultSync ProGuard / R8 Rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Kotlinx Serialization
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class com.obsidiandrivesync.**$$serializer { *; }
-keepclassmembers class com.obsidiandrivesync.** {
    *** Companion;
}

# Room entities
-keep class com.obsidiandrivesync.data.db.**Entity { *; }

# Keep WorkManager custom Worker
-keep class com.obsidiandrivesync.worker.SyncWorker { *; }
