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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.airtouch.gesture.ControlMode
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
    var mode by remember { mutableStateOf(settings.controlMode) }
    var showSettings by remember { mutableStateOf(false) }
    val ready = cameraOk && a11yOk

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("AirTouch", style = MaterialTheme.typography.headlineSmall)
            Text(
                statusLine(status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            PowerButton(on = status.isRunning, enabled = ready || status.isRunning) {
                if (status.isRunning) GestureCameraService.stop(context) else GestureCameraService.start(context)
            }

            if (status.isRunning && status.fps > 0) {
                Text(
                    "${status.fps} кадров/с" + (if (status.lowLight) " · темно — подсвечиваю" else "") +
                        (if (status.unsteady) " · тряска: устойчивый режим" else "") +
                        (if (status.landscape) " · альбом" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!ready || !notificationsOk) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Нужно один раз разрешить", style = MaterialTheme.typography.titleSmall)
                    SetupRow("Камера", cameraOk) { cameraLauncher.launch(Manifest.permission.CAMERA) }
                    SetupRow("Уведомления", notificationsOk) {
                        if (Build.VERSION.SDK_INT >= 33) notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    SetupRow("Специальные возможности → AirTouch", a11yOk) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    if (!a11yOk) {
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        }) { Text("Переключатель серый? О приложении → ⋮ → Разрешить ограниченные настройки") }
                    }
                }
            }

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (mode == ControlMode.PINCH) {
                    HowTo("Двигайте рукой — курсор едет, как на тачпаде")
                    HowTo("Сведите большой и указательный — нажатие")
                    HowTo("Сведите и махните — листать")
                    HowTo("Курсор к нижнему краю — кнопки ◁ ○ ▢")
                    HowTo("На клавиатуре: щипок — буква, щипок и провести по буквам — слово")
                } else {
                    HowTo("Мах рукой — листать")
                    HowTo("Тык рукой к экрану — домой")
                    HowTo("✌️ неподвижно — назад")
                }
            }

            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "Скрыть настройки" else "Настройки")
            }
            if (showSettings) {
                ModeCard(mode) { mode = it; settings.controlMode = it }
                SettingsCard(settings, mode)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun statusLine(s: AirTouchState.Status): String = when (s.mode) {
    Mode.OFF -> "Выключено"
    Mode.ACTIVE -> when {
        s.dockVisible -> "Панель кнопок"
        s.handVisible -> "Рука видна"
        else -> "Покажите руку камере"
    }
    Mode.IDLE -> "Жду руку"
    Mode.PAUSED -> "Пауза — экран был выключен"
    Mode.ERROR -> s.error ?: "Ошибка"
}

@Composable
private fun PowerButton(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = when {
        on -> MaterialTheme.colorScheme.primary
        enabled -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = bg,
        modifier = Modifier.size(150.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (on) "Вкл" else "Выкл",
                style = MaterialTheme.typography.titleLarge,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupRow(title: String, done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "✓" else "•", modifier = Modifier.width(24.dp), color = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!done) TextButton(onClick = onClick) { Text("Разрешить") }
    }
}

@Composable
private fun HowTo(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ModeCard(mode: ControlMode, onChange: (ControlMode) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Способ управления", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == ControlMode.PINCH,
                    onClick = { onChange(ControlMode.PINCH) },
                    label = { Text("🤏 Щипок как палец") },
                )
                FilterChip(
                    selected = mode == ControlMode.GESTURES,
                    onClick = { onChange(ControlMode.GESTURES) },
                    label = { Text("👋 Жесты махами") },
                )
            }
            Text(
                if (mode == ControlMode.PINCH) {
                    "Рекомендую. Как на Vision Pro: рука — курсор, всё делает только щипок. Без щипка ничего не нажмётся."
                } else {
                    "Мах, тык, «V». Быстрее для ленты, но жесты иногда путаются."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SettingsCard(settings: AirTouchSettings, mode: ControlMode) {
    var sensitivity by remember { mutableFloatStateOf(settings.sensitivity) }
    var deadZone by remember { mutableFloatStateOf(settings.deadZone) }
    var topThird by remember { mutableStateOf(settings.topThirdOnly) }
    var vertical by remember { mutableStateOf(settings.verticalSwipes) }
    var front by remember { mutableStateOf(settings.useFrontCamera) }
    var cursor by remember { mutableStateOf(settings.cursorEnabled) }
    var invert by remember { mutableStateOf(settings.invertSwipes) }
    var airKeyboard by remember { mutableStateOf(settings.airKeyboard) }
    var screenLight by remember { mutableStateOf(settings.screenLight) }
    var stabilize by remember { mutableStateOf(settings.stabilize) }
    var stealth by remember { mutableStateOf(settings.stealth) }
    var quietNotif by remember { mutableStateOf(settings.quietNotification) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Настройки", style = MaterialTheme.typography.titleMedium)

            if (mode == ControlMode.GESTURES) SwitchRow(
                title = "Режим курсора",
                subtitle = "☝️ палец — курсор, 🤏 щипок — нажать, щипок и вести — прокрутка, " +
                    "щипок и держать — долгое нажатие. Пока включено, палец не машет — машите ладонью.",
                checked = cursor,
            ) { cursor = it; settings.cursorEnabled = it }

            SwitchRow(
                title = "Невидимый режим",
                subtitle = "Ни плавающей кнопки, ни зелёной точки, ни вспышек. Курсор — обычная стрелка мыши Android: " +
                    "появляется, когда рука движется, и исчезает через секунду покоя; щипок — тонкий кружок касания. " +
                    "Панель «Назад / Домой» выезжает, когда опускаете руку к низу. Включать и выключать — здесь или из уведомления.",
                checked = stealth,
            ) { stealth = it; settings.stealth = it }

            SwitchRow(
                title = "Тихое уведомление",
                subtitle = "Уведомление о камере свёрнуто в одну строку внизу шторки и без значка в строке состояния " +
                    "(совсем убрать его Android не разрешает: камера работает в фоне).",
                checked = quietNotif,
            ) { quietNotif = it; settings.quietNotification = it }

            SwitchRow(
                title = "Стабилизация при ходьбе",
                subtitle = "Гироскоп вычитает тряску телефона из движения руки, а когда вы идёте, " +
                    "курсор переходит в устойчивый режим: сильнее сглаживание, больше мёртвая зона.",
                checked = stabilize,
            ) { stabilize = it; settings.stabilize = it }

            SwitchRow(
                title = "Подсветка в темноте",
                subtitle = "Когда темно: яркость экрана 100% и светлая рамка по краям — экран освещает руку. " +
                    "Камера сама переходит на длинную выдержку и максимальную чувствительность.",
                checked = screenLight,
            ) { screenLight = it; settings.screenLight = it }
            val ctx = LocalContext.current
            if (screenLight && !Settings.System.canWrite(ctx)) {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Разрешить менять яркость экрана") }
            }

            SwitchRow(
                title = "Своя воздушная клавиатура (эксперимент)",
                subtitle = "Открыли поле ввода — поверх обычной клавиатуры появляется воздушная. " +
                    "🤏 щипок и росчерк через буквы — слово, быстрый щипок — буква, ✌️ — закрыть.",
                checked = airKeyboard,
            ) { airKeyboard = it; settings.airKeyboard = it }

            if (mode == ControlMode.GESTURES) {
                SwitchRow(
                    title = "Обратное направление махов",
                    subtitle = "Если кажется, что листает «не туда»",
                    checked = invert,
                ) { invert = it; settings.invertSwipes = it }

                Text("Чувствительность махов: ×${"%.1f".format(sensitivity)}")
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
            }

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
