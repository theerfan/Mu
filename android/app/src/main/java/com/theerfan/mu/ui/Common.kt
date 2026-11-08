package com.theerfan.mu.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theerfan.mu.audio.AudioSession
import kotlin.math.abs

/** Full-screen gradient backdrop with soft colour glows; content is inset from system bars and cutouts. */
@Composable
fun MuScreen(modifier: Modifier = Modifier, insets: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().muBackground().then(if (insets) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier),
        content = content,
    )
}

fun Modifier.muBackground(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(MuColors.bgTop, MuColors.bg), endY = size.height * 0.75f))
    // a faint green glow in the corner, nothing more
    drawCircle(Brush.radialGradient(listOf(MuColors.primary.copy(alpha = 0.07f), Color.Transparent), Offset(size.width * 0.9f, 0f), size.minDimension * 0.8f),
        size.minDimension * 0.8f, Offset(size.width * 0.9f, 0f))
}

/** Transparent top bar: round back button, bold title, optional trailing actions. */
@Composable
fun MuTopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(14.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = MuColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, size: Dp = 44.dp, color: Color = MuColors.surfaceHi, tint: Color = MuColors.text) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color).border(1.dp, MuColors.stroke, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

/**
 * The signature game-style button: a bright face sitting on a darker "lip" that sinks when pressed.
 */
@Composable
fun ChunkyButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MuColors.primary, lip: Color = MuColors.primaryLip,
    textColor: Color = MuColors.onPrimary, icon: ImageVector? = null, enabled: Boolean = true, height: Dp = 60.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lipH = 6.dp
    val sink by animateDpAsState(if (pressed && enabled) lipH - 1.dp else 0.dp, tween(70), label = "sink")
    val shape = RoundedCornerShape(18.dp)
    val face = if (enabled) color else MuColors.surfaceHi
    Box(modifier.height(height).clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)) {
        Box(Modifier.matchParentSize().padding(top = lipH).clip(shape).background(if (enabled) lip else MuColors.surface))
        Box(
            Modifier.fillMaxWidth().height(height - lipH).offset(y = sink).clip(shape)
                .background(Brush.verticalGradient(listOf(lerp(face, Color.White, 0.18f), face))),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                val fg = if (enabled) textColor else MuColors.textMuted
                if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(8.dp)) }
                Text(text.uppercase(), color = fg, fontWeight = FontWeight.Black, fontSize = 17.sp, letterSpacing = 1.2.sp)
            }
        }
    }
}

/** Rounded surface card; optional gradient fill and click. */
@Composable
fun MuCard(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, brush: Brush? = null, shape: Shape = RoundedCornerShape(24.dp),
    padding: Dp = 18.dp, verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(shape).then(if (brush != null) Modifier.background(brush) else Modifier.background(MuColors.surface))
            .border(1.dp, MuColors.stroke, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = verticalArrangement, content = content,
    )
}

/** Small rounded label, e.g. "92 bpm". */
@Composable
fun Pill(text: String, color: Color = MuColors.textDim, background: Color = Color(0x1AFFFFFF), icon: ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun Stars(count: Int, modifier: Modifier = Modifier, max: Int = 3, size: Dp = 18.dp, spacing: Dp = 2.dp, empty: Color = Color(0x33FFFFFF)) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        repeat(max) { Icon(Icons.Filled.Star, null, tint = if (it < count) MuColors.star else empty, modifier = Modifier.size(size)) }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = OverlineStyle, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = OverlineStyle)
    }
}

/** Coloured notice strip with an optional action. */
@Composable
fun Banner(text: String, color: Color = MuColors.ok, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(text, color = MuColors.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action.uppercase(), color = color, fontWeight = FontWeight.Black) }
    }
}

/** Big label + value block used in stat rows. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = MuColors.text) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label.uppercase(), style = OverlineStyle, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

/** Procedural square cover: gradient picked from the title, six "strings" and the title's initial. */
@Composable
fun SongCover(title: String, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val (a, b) = MuColors.covers[abs(title.hashCode()) % MuColors.covers.size]
    val initial = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            for (s in 0 until 6) {
                val y = this.size.height * (0.18f + s * 0.13f)
                drawLine(Color.White.copy(alpha = 0.10f + s * 0.02f), Offset(0f, y + this.size.height * 0.12f), Offset(this.size.width, y - this.size.height * 0.12f), strokeWidth = 1.5f + s * 0.5f)
            }
            drawCircle(Color.White.copy(alpha = 0.12f), this.size.minDimension * 0.34f, Offset(this.size.width * 0.78f, this.size.height * 0.82f))
        }
        Text(initial, color = Color.White, fontSize = (size.value * 0.44f).sp, fontWeight = FontWeight.Black)
    }
}

/** Circular progress ring with centred content. */
@Composable
fun Ring(fraction: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 10.dp, content: @Composable () -> Unit = {}) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = stroke.toPx()
            val sz = androidx.compose.ui.geometry.Size(size.minDimension - w, size.minDimension - w)
            val tl = Offset((size.width - sz.width) / 2, (size.height - sz.height) / 2)
            drawArc(Color(0x22FFFFFF), 0f, 360f, false, tl, sz, style = Stroke(w))
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
fun MuDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, dismiss: String? = null, danger: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = MuColors.surfaceHi, shape = RoundedCornerShape(28.dp),
        title = { Text(title, fontWeight = FontWeight.ExtraBold) },
        text = { Text(text, color = MuColors.textDim) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm.uppercase(), color = if (danger) MuColors.wrong else MuColors.primary, fontWeight = FontWeight.Black) } },
        dismissButton = dismiss?.let { { TextButton(onClick = onDismiss) { Text(it.uppercase(), color = MuColors.textDim, fontWeight = FontWeight.Bold) } } },
    )
}

/** Keeps the display on while this composable is shown (playing, tuning, calibrating). */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
}

/** Holds the audio engine while this composable is on screen and the activity is resumed. Returns ready state. */
@Composable
fun rememberAudio(enabled: Boolean): Pair<Boolean, String?> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(enabled, lifecycle) {
        var held = false
        fun acquire() { if (enabled && !held) { held = AudioSession.acquire(context); ready = held; error = AudioSession.lastError } }
        fun release() { if (held) { AudioSession.release(); held = false; ready = false } }
        val obs = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_RESUME -> acquire(); Lifecycle.Event.ON_PAUSE -> release(); else -> {} }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) acquire()
        onDispose { lifecycle.removeObserver(obs); release() }
    }
    return ready to error
}

@Composable
fun MicNeeded(requestMic: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(MuColors.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            MicGlyph(Modifier.size(56.dp), MuColors.accent)
        }
        Spacer(Modifier.height(24.dp))
        Text("Let Mu listen", style = MaterialTheme.typography.headlineSmall, color = MuColors.text)
        Spacer(Modifier.height(8.dp))
        Text("Mu uses the microphone to hear your guitar and score every note you play. Audio never leaves your phone.",
            color = MuColors.textDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        ChunkyButton("Allow microphone", requestMic, Modifier.fillMaxWidth())
    }
}

// ---- Small hand-drawn glyphs (the core icon set has no music icons) ----

@Composable
fun MicGlyph(modifier: Modifier, color: Color) = Canvas(modifier) {
    val w = size.width; val h = size.height; val sw = w * 0.09f
    drawRoundRect(color, Offset(w * 0.34f, h * 0.06f), androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.54f), androidx.compose.ui.geometry.CornerRadius(w * 0.16f))
    drawArc(color, 0f, 180f, false, Offset(w * 0.2f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.5f), style = Stroke(sw, cap = StrokeCap.Round))
    drawLine(color, Offset(w / 2, h * 0.72f), Offset(w / 2, h * 0.9f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.34f, h * 0.92f), Offset(w * 0.66f, h * 0.92f), sw, StrokeCap.Round)
}

/** Tuner: a half gauge with a needle. */
@Composable
fun GaugeGlyph(modifier: Modifier, color: Color) = Canvas(modifier) {
    val w = size.width; val sw = w * 0.1f
    drawArc(color, 180f, 180f, false, Offset(sw, w * 0.2f), androidx.compose.ui.geometry.Size(w - 2 * sw, w - 2 * sw), style = Stroke(sw, cap = StrokeCap.Round))
    drawLine(color, Offset(w / 2, w * 0.7f), Offset(w * 0.68f, w * 0.36f), sw, StrokeCap.Round)
    drawCircle(color, sw, Offset(w / 2, w * 0.7f))
}

/** Calibration: sound waves around a dot. */
@Composable
fun WaveGlyph(modifier: Modifier, color: Color) = Canvas(modifier) {
    val w = size.width; val c = Offset(w * 0.3f, size.height / 2); val sw = w * 0.09f
    drawCircle(color, w * 0.1f, c)
    for (k in 1..2) {
        val r = w * (0.22f + k * 0.18f)
        drawArc(color, -45f, 90f, false, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Metronome-ish tempo glyph. */
@Composable
fun TempoGlyph(modifier: Modifier, color: Color) = Canvas(modifier) {
    val w = size.width; val h = size.height; val sw = w * 0.09f
    val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.32f, h * 0.1f); lineTo(w * 0.68f, h * 0.1f); lineTo(w * 0.86f, h * 0.9f); lineTo(w * 0.14f, h * 0.9f); close() }
    drawPath(p, color, style = Stroke(sw, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawLine(color, Offset(w / 2, h * 0.72f), Offset(w * 0.74f, h * 0.24f), sw, StrokeCap.Round)
}

/** Streak flame (not in the core icon set). */
val FlameIcon: ImageVector by lazy {
    ImageVector.Builder("Flame", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f)
            curveTo(13f, 6f, 18.5f, 8f, 18.5f, 14f)
            curveTo(18.5f, 18.4f, 15.6f, 22f, 12f, 22f)
            curveTo(8.4f, 22f, 5.5f, 18.4f, 5.5f, 14.5f)
            curveTo(5.5f, 11.5f, 7f, 9.5f, 8.8f, 8.3f)
            curveTo(8.8f, 11f, 10f, 12.6f, 11.6f, 13.2f)
            curveTo(10.8f, 9f, 11f, 5f, 12f, 2f)
            close()
        }
    }.build()
}
