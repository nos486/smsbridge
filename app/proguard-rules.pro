# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# WorkManager instantiates workers reflectively
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
