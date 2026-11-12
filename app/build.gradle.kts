import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

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
        versionCode = 6
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

// ---- Модель MediaPipe Gesture Recognizer (рука + обученный классификатор жестов, ~8 МБ) ----
val handModelUrl =
    "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
val handModelFile = layout.projectDirectory.file("src/main/assets/gesture_recognizer.task")

val downloadHandModel by tasks.registering {
    description = "Downloads MediaPipe gesture_recognizer.task into assets"
    outputs.file(handModelFile)
    onlyIf { !handModelFile.asFile.exists() }
    doLast {
        val target = handModelFile.asFile
        target.parentFile.mkdirs()
        URI(handModelUrl).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        logger.lifecycle("Downloaded gesture_recognizer.task (${target.length() / 1024} KB)")
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
    debugImplementation("androidx.recyclerview:recyclerview:1.3.2") // только тестовая «лента как Shorts»

    // Сквозные тесты на эмуляторе: синтетическая рука → движок → служба → настоящие View.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
}
