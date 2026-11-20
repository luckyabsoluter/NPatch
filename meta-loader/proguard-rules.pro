-keep class top.nkbe.npatch.metaloader.** {
    *;
}
# The patch loader resolves classes through the meta-loader's class loader first.
-repackageclasses top.nkbe.npatch.metaloader.internal
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class * extends androidx.room.Entity {
    <fields>;
}
-keep interface * extends androidx.room.Dao {
    <methods>;
}

-dontwarn androidx.annotation.NonNull
-dontwarn androidx.annotation.Nullable
-dontwarn androidx.annotation.VisibleForTesting
