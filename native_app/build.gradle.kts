// Bunbeat 原生版（Kotlin）根工程。
// 与 android_app/android 保持同一套工具链：AGP 9.1.0 + Kotlin 2.4.0 + Gradle 9.3.1，
// 这三者的发行包/依赖都在本机 Gradle 缓存里，因此可以完全离线构建（--offline）。
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.android") apply false
}
