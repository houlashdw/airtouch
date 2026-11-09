package com.airtouch.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtouch.core.AirTouchState
import com.airtouch.core.AirTouchState.Mode
import com.airtouch.gesture.AirGesture
import com.airtouch.gesture.HandPose
import com.airtouch.gesture.SystemAction
import com.airtouch.service.AirInputService
import com.airtouch.service.GestureCameraService
import com.airtouch.settings.AirTouchSettings
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) { AirTouchScreen() }
        }
    }
}

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun AirTouchScreen() {
    val context = LocalContext.current
    val settings = remember { AirTouchSettings(context) }

    var cameraOk by remember { mutableStateOf(GestureCameraService.hasCameraPermission(context)) }
    var notificationsOk by remember { mutableStateOf(notificationsGranted(context)) }
    var a11yOk by remember { mutableStateOf(AirInputService.isEnabledInSettings(context)) }

    // Пользователь возвращается из системных настроек — перепроверяем всё.
    LifecycleResumeEffect(Unit) {
        cameraOk = GestureCameraService.hasCameraPermission(context)
        notificationsOk = notificationsGranted(context)
        a11yOk = AirInputService.isEnabledInSettings(context)
        onPauseOrDispose { }
    }

    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraOk = it }
    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsOk = it }

    val status by AirTouchState.status.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AirTouch", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Листайте ленты, фото и страницы махом ладони. Распознавание идёт только на телефоне.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("Настройка", style = MaterialTheme.typography.titleMedium)
            StepCard(
                n = 1, title = "Камера", done = cameraOk,
                text = "Нужна, чтобы видеть руку. Кадры не сохраняются и никуда не отправляются.",
                action = "Разрешить",
            ) { cameraLauncher.launch(Manifest.permission.CAMERA) }

            StepCard(
                n = 2, title = "Уведомления", done = notificationsOk,
                text = "Пока камера включена, в шторке висит уведомление с кнопкой «Стоп».",
                action = "Разрешить",
            ) {
                if (Build.VERSION.SDK_INT >= 33) notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            StepCard(
                n = 3, title = "Служба специальных возможностей", done = a11yOk,
                text = "Через неё AirTouch делает свайпы в любых приложениях и показывает плавающую кнопку. " +
                    "Откройте «Специальные возможности» → «Установленные приложения» → AirTouch → включить.\n\n" +
                    "Если переключатель серый (Android 13+, установка не из магазина): " +
                    "«О приложении» → ⋮ → «Разрешить ограниченные настройки».",
                action = "Открыть настройки",
                secondaryAction = "О приложении",
                onSecondary = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    )
                },
            ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

            ControlCard(status = status, ready = cameraOk && a11yOk) {
                if (status.isRunning) GestureCameraService.stop(context) else GestureCameraService.start(context)
            }

            SettingsCard(settings)

            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Жесты", style = MaterialTheme.typography.titleMedium)
                    Text("✋ Ладонь, быстрый мах — листать ←→↑↓")
                    Text("☝️ Указательный палец — курсор")
                    Text("🤏 Щипок (большой + указательный, остальные согнуты) — нажать")
                    Text("🤏 Щипок и вести — прокрутка / перетаскивание")
                    Text("🤏 Щипок и держать — долгое нажатие")
                    Text("👌 «Нолик» (щипок, три пальца вверх) держать — домой")
                    Text("👌 «Нолик» и отвести руку к себе — уменьшить, к телефону — увеличить")
                    Text("✌️ «V» держать — назад")
                    Text("✊ Кулак держать 1 с — недавние приложения")
                    Text("Удержание показывает кольцо — передумали, смените позу, пока кольцо не замкнулось.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Советы", style = MaterialTheme.typography.titleMedium)
                    Text("• Рука целиком в кадре фронталки: 20–40 см от телефона, не вплотную.")
                    Text("• Покажите руку, затем мах. Подъём руки в кадр не считается.")
                    Text("• Листать подряд можно без пауз; в обратную сторону — задержите руку на миг.")
                    Text("• Резкий мах «за край кадра» тоже засчитывается.")
                    Text("• В темноте точность падает — включите свет.")
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    n: Int,
    title: String,
    text: String,
    done: Boolean,
    action: String,
    secondaryAction: String? = null,
    onSecondary: () -> Unit = {},
    onAction: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (done) "✅ " else "$n. ") + title,
                style = MaterialTheme.typography.titleSmall,
            )
            if (!done) {
                Text(text, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAction) { Text(action) }
                    if (secondaryAction != null) TextButton(onClick = onSecondary) { Text(secondaryAction) }
                }
            }
        }
    }
}

@Composable
private fun ControlCard(status: AirTouchState.Status, ready: Boolean, onToggle: () -> Unit) {
    val stateText = when (status.mode) {
        Mode.OFF -> "Выключено"
        Mode.ACTIVE -> if (status.handVisible) "Рука видна ✋" else "Ищу руку…"
        Mode.IDLE -> "Жду руку (экономный режим)"
        Mode.PAUSED -> "Пауза: экран был выключен"
        Mode.ERROR -> status.error ?: "Ошибка"
    }
    val lastGesture = when (status.lastGesture) {
        AirGesture.SwipeLeft -> "← влево"
        AirGesture.SwipeRight -> "→ вправо"
        AirGesture.SwipeUp -> "↑ вверх"
        AirGesture.SwipeDown -> "↓ вниз"
        null -> null
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Режим жестов", style = MaterialTheme.typography.titleMedium)
            Text(stateText)
            if (status.isRunning && status.lowLight) {
                Text("⚠️ Мало света — точность снижена", color = MaterialTheme.colorScheme.error)
            }
            if (status.mode == Mode.ACTIVE) {
                val seen = when {
                    status.holdAction == SystemAction.HOME -> "нолик 👌 — держите: домой"
                    status.holdAction == SystemAction.BACK -> "V ✌️ — держите: назад"
                    status.holdAction == SystemAction.RECENTS -> "кулак ✊ — держите: недавние"
                    status.zooming -> "нолик 👌 — масштаб"
                    status.cursorVisible && status.pinching -> "щипок 🤏 — нажатие"
                    status.cursorVisible -> "палец ☝️ — курсор"
                    else -> when (status.pose) {
                        HandPose.OPEN_PALM -> "ладонь ✋ — можно махать"
                        HandPose.POINTING -> "палец ☝️ — можно махать"
                        HandPose.V_SIGN, HandPose.OTHER -> "руку — можно махать"
                        HandPose.FIST -> "кулак ✊"
                        null -> "пока ничего. Отодвиньте руку на 20–40 см, чтобы она целиком попала в камеру"
                    }
                }
                Text("Вижу: $seen")
            }
            if (lastGesture != null) Text("Последний жест: $lastGesture")
            Button(onClick = onToggle, enabled = ready || status.isRunning, modifier = Modifier.fillMaxWidth()) {
                Text(if (status.isRunning) "Выключить" else "Включить")
            }
            if (status.isRunning) {
                Text(
                    "Работает, пока горит экран. Экран погас — пауза, включили — продолжает сам. " +
                        "Камера включена постоянно, поэтому батарея садится быстрее.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!ready) {
                Text("Сначала пройдите шаги настройки выше.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "Дальше можно управлять плавающей кнопкой ✋ поверх любых приложений.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(settings: AirTouchSettings) {
    var sensitivity by remember { mutableFloatStateOf(settings.sensitivity) }
    var deadZone by remember { mutableFloatStateOf(settings.deadZone) }
    var topThird by remember { mutableStateOf(settings.topThirdOnly) }
    var vertical by remember { mutableStateOf(settings.verticalSwipes) }
    var front by remember { mutableStateOf(settings.useFrontCamera) }
    var cursor by remember { mutableStateOf(settings.cursorEnabled) }
    var invert by remember { mutableStateOf(settings.invertSwipes) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Настройки", style = MaterialTheme.typography.titleMedium)

            SwitchRow(
                title = "Режим курсора",
                subtitle = "Палец — курсор. Щипок (большой + указательный) — нажать, " +
                    "щипок и вести — прокрутка, щипок и держать — долгое нажатие. Ладонь — махи.",
                checked = cursor,
            ) { cursor = it; settings.cursorEnabled = it }

            SwitchRow(
                title = "Обратное направление махов",
                subtitle = "Если кажется, что листает «не туда»",
                checked = invert,
            ) { invert = it; settings.invertSwipes = it }

            Text("Чувствительность: ×${"%.1f".format(sensitivity)}")
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it; settings.sensitivity = it },
                valueRange = 0.5f..2f,
            )

            Text("Мёртвая зона (гасит дрожание руки): ${(deadZone * 1000).roundToInt()}")
            Slider(
                value = deadZone,
                onValueChange = { deadZone = it; settings.deadZone = it },
                valueRange = 0f..0.04f,
            )

            SwitchRow(
                title = "Только верхняя треть кадра",
                subtitle = "Рука ниже — например, при селфи — игнорируется",
                checked = topThird,
            ) { topThird = it; settings.topThirdOnly = it }

            SwitchRow(
                title = "Вертикальные свайпы",
                subtitle = "Мах вверх/вниз листает ленту",
                checked = vertical,
            ) { vertical = it; settings.verticalSwipes = it }

            Text("Камера", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = front,
                    onClick = { front = true; settings.useFrontCamera = true },
                    label = { Text("Фронтальная") },
                )
                FilterChip(
                    selected = !front,
                    onClick = { front = false; settings.useFrontCamera = false },
                    label = { Text("Основная") },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
