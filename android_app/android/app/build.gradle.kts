plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.run_bpm_android"
    // 本机仅安装了 android-37.0 平台，故固定编译 SDK 为 37
    compileSdk = 37
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.run_bpm_android"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
            // Disable R8 minify/shrink: the bundled ffmpeg-kit native library + reflection
            // trips R8 "Missing classes" on com.arthenica.smartexception.java.Exceptions.
            // Minification is optional (only reduces size) and not needed for a working APK.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    // PATCH: guarantee com.arthenica.smartexception.java.Exceptions is on the app's
    // dex path. The maintained ffmpeg-kit-audio AAR references it at FFmpegKitConfig
    // <clinit>, but its POM does not pull smart-exception transitively. Declaring it
    // directly here ensures it is debuggable/packaged deterministically for all ABIs.
    implementation("com.arthenica:smart-exception-java:0.2.1")
}
