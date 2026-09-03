# Keep the yt-dlp Android wrapper and its reflection/JSON models intact.
-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn com.fasterxml.jackson.**

# Rhino JS engine references java.beans (not available on Android)
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.ScriptEngineFactory
