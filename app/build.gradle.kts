import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.airtouch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airtouch"
        minSdk = 30          // флагманы; API 30 даёт currentWindowMetrics и стабильный dispatchGesture
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-mvp"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // MediaPipe открывает модель через AssetManager.openFd — файл не должен сжиматься.
    androidResources {
        noCompress += "task"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ---- Модель MediaPipe Hand Landmarker (~7.8 МБ) скачивается один раз при первой сборке ----
val handModelUrl =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
val handModelFile = layout.projectDirectory.file("src/main/assets/hand_landmarker.task")

val downloadHandModel by tasks.registering {
    description = "Downloads MediaPipe hand_landmarker.task into assets"
    outputs.file(handModelFile)
    onlyIf { !handModelFile.asFile.exists() }
    doLast {
        val target = handModelFile.asFile
        target.parentFile.mkdirs()
        java.net.URI(handModelUrl).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        logger.lifecycle("Downloaded hand_landmarker.task (${target.length() / 1024} KB)")
    }
}
tasks.named("preBuild") { dependsOn(downloadHandModel) }

dependencies {
    // Версии на момент написания — Android Studio подскажет более свежие.
    val cameraX = "1.4.2"
    val lifecycle = "2.8.7"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-service:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycle")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycle")

    implementation(platform("androidx.compose:compose-bom:2025.04.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")

    implementation("com.google.mediapipe:tasks-vision:0.10.26")

    testImplementation("junit:junit:4.13.2")
}
