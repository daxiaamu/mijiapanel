# Keep module-owned Android components, Xposed entry points, and callbacks that
# cross process/reflection boundaries. R8 can still optimize dependencies and
# remove unused resources.
-keep class com.daxiaamu.mijiapanel.** { *; }

# Pose Detection pulls in MediaPipe's model-management path, which initializes
# WorkManager and its generated Room database at process start. Preserve only
# the name-resolved database boundary; dependency consumer rules cover CameraX,
# ML Kit, AndroidX Startup, and the remainder of WorkManager.
-keep class androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# ML Kit discovers these registrars from AndroidManifest meta-data using their
# original class names. R8 cannot see that reflective edge and otherwise removes
# the Face/Pose factories, causing FaceDetection.getClient()/PoseDetection.getClient()
# to return through a broken component graph at runtime.
-keep class com.google.mlkit.vision.face.internal.FaceRegistrar { *; }
-keep class com.google.mlkit.vision.pose.internal.PoseRegistrar { *; }
-keep class com.google.mlkit.vision.common.internal.VisionCommonRegistrar { *; }
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }

# Keep metadata used by AndroidX, CameraX, ML Kit, and the Modern Xposed API.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
