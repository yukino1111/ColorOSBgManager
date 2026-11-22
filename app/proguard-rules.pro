-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep class io.github.yukino1111.colorosbgmanager.xposed.** {
    <init>();
    *;
}
-keep class io.github.yukino1111.colorosbgmanager.model.** { *; }
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**
