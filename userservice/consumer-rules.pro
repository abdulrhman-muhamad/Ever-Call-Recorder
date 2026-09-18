# Shizuku launches RecorderService by FQCN reflection inside app_process.
# R8 must not rename/strip the class, its no-arg constructor, or its public
# AIDL stub methods.
-keep class com.coolappstore.evercallrecorder.by.svhp.userservice.RecorderService { *; }
-keep class com.coolappstore.evercallrecorder.by.svhp.userservice.HiddenApiBootstrap { *; }

# AudioRecorderJob reflectively touches AudioRecord internals on some HALs.
-keep class com.coolappstore.evercallrecorder.by.svhp.userservice.AudioRecorderJob { *; }

# AIDL-generated stubs.
-keep class com.coolappstore.evercallrecorder.by.svhp.aidl.** { *; }

# HiddenApiBypass uses unsafe field setters; keep its surface intact.
-keep class org.lsposed.hiddenapibypass.** { *; }
