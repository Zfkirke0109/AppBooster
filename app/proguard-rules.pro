# Keep annotation and generic metadata used by Hilt, Room, and generated binders.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# Shizuku binds this service class across process boundaries. Keep the stable
# service implementation and the AIDL/Binder contract names intact.
-keep class com.tony.appbooster.data.client.ShellService { *; }
-keep class com.tony.appbooster.IShellService { *; }
-keep class com.tony.appbooster.IShellService$Stub { *; }
-keep class com.tony.appbooster.IShellService$Stub$Proxy { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# WorkManager persists worker class names. Keep names so queued work can resume
# after app updates and process death.
-keepnames class * extends androidx.work.ListenableWorker
-keep class com.tony.appbooster.presentation.worker.** { *; }

# Hilt generated entry points and injected constructors are normally covered by
# library consumer rules, but these keeps protect release builds if dependency
# rules change.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory { *; }
-keep class **_HiltModules_* { *; }
-keep class **_Factory { *; }

# Room reflects over annotations during schema validation and uses generated
# implementation names. Keep database, DAO, and entity types.
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
