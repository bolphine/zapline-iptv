package com.zapline.iptv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zapline.iptv.data.Programme
import com.zapline.iptv.data.StreamInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hmFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.UK) }
fun hm(t: Long): String = hmFmt.get().format(Date(t))

fun dayWord(t: Long, now: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now; val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.timeInMillis = t
    return when (cal.get(java.util.Calendar.DAY_OF_YEAR) - today) {
        0 -> ""; 1 -> " tomorrow"; -1 -> " yesterday"
        else -> " " + SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(t))
    }
}

fun initials(name: String): String =
    name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

/** Channel logo on a tile, with initials while it loads or when there's none. */
@Composable
fun Logo(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size / 5)).background(Palette.Dusk), contentAlignment = Alignment.Center) {
        Text(initials(name), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
        if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(size / 10))
    }
}

/** Poster or artwork, cropped to fill. */
@Composable
fun Art(url: String?, modifier: Modifier = Modifier) {
    if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
fun Badge(text: String, color: Color = Palette.Muted, filled: Boolean = false) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(5.dp))
            .then(if (filled) Modifier.background(color) else Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(5.dp)))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, color = if (filled) Palette.OnTally else color, maxLines = 1,
    )
}

/** Picture and sound badges for what checking found ("4K", "HDR", "5.1"…). */
@Composable
fun QualityBadges(info: StreamInfo?, labelled4k: Boolean = false, compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            info != null && info.dead -> Badge("Not working", Palette.Alert)
            info != null && info.ok && info.resolution != null -> {
                val r = info.resolution!!
                Badge(r, if (r == "4K") Palette.Tally else if (info.height >= 1000) Palette.TallyText else Palette.Muted, filled = r == "4K")
                if (!compact) {
                    info.audio?.takeIf { it != "Stereo" && it != "Mono" }?.let { Badge(it, Palette.Lilac) }
                    if (info.videoCodec == "HEVC") Badge("HEVC", Palette.Muted)
                }
            }
            labelled4k -> Badge("4K?", Palette.Tally)
        }
    }
}

@Composable
fun ProgressLine(p: Programme, now: Long, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val f = ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(modifier.height(height).clip(RoundedCornerShape(height)).background(Palette.LineSoft)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Palette.Tally))
    }
}

/** A toggle/filter control: quiet when off, accent outline when on. */
@Composable
fun Pill(
    text: String,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.height(34.dp).clip(shape)
            .background(when { accent -> Palette.Tally; active -> Palette.TallyDim; else -> Palette.Night })
            .border(1.dp, if (active && !accent) Palette.Tally.copy(alpha = 0.7f) else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { Icon(it, null, Modifier.size(16.dp).padding(end = 0.dp), tint = if (accent) Palette.OnTally else if (active) Palette.Tally else Palette.Muted) }
        if (leading != null) Box(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = when { accent -> Palette.OnTally; active -> Palette.TallyText; else -> Palette.Text })
    }
}

/** Icon button with a tooltip naming what it does (and its shortcut). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToolButton(icon: ImageVector, label: String, active: Boolean = false, size: Dp = 40.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip(containerColor = Palette.Haze) { Text(label, color = Palette.Text) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size / 4))
                .background(when { active -> Palette.TallyDim; hovered && enabled -> Palette.Dusk; else -> Color.Transparent })
                .hoverable(src).clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, Modifier.size(size * 0.55f), tint = when { !enabled -> Palette.Faint; active -> Palette.Tally; else -> Palette.Text })
        }
    }
}

@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    Row(
        modifier.height(42.dp).clip(RoundedCornerShape(21.dp)).background(Palette.Night)
            .border(1.dp, Palette.LineSoft, RoundedCornerShape(21.dp)).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = Palette.Muted)
        Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = Palette.Faint, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text),
                cursorBrush = SolidColor(Palette.Tally),
                modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
            )
        }
        if (value.isNotEmpty()) ToolButton(Icons.Default.Clear, "Clear", size = 30.dp) { onChange("") }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Palette.Faint, fontWeight = FontWeight.Bold)
        trailing()
    }
}

@Composable
fun EmptyState(title: String, body: String? = null, action: String? = null, modifier: Modifier = Modifier, onAction: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        body?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, textAlign = TextAlign.Center) }
        action?.let { Pill(it, accent = true, modifier = Modifier.padding(top = 16.dp), onClick = onAction) }
    }
}

/** A pill that opens a menu of choices. */
@Composable
fun <T> ChoicePill(label: String, choices: List<Pair<T, String>>, current: T?, active: Boolean = current != null, leading: ImageVector? = null, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(label, active = active, leading = leading) { open = true }
        DropdownMenu(open, { open = false }, containerColor = Palette.Night) {
            choices.forEach { (v, l) ->
                DropdownMenuItem(text = { Text(l, color = if (v == current) Palette.Tally else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { open = false; onPick(v) })
            }
        }
    }
}

/** The thin rule between panels. */
@Composable
fun VDivider() = Box(Modifier.fillMaxHeight().width(1.dp).background(Palette.LineSoft))
