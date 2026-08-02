# Keep the module's Android components, Xposed entry points, and reflective
# callback paths intact. Third-party dependencies and resources can still be
# optimized and shrunk.
-keep class com.daxiaamu.mijiapanel.** { *; }

# Keep metadata used by AndroidX, CameraX, ML Kit, and the Modern Xposed API.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
