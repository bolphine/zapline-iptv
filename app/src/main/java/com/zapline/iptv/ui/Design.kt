package com.zapline.iptv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.zapline.iptv.data.Programme

/** Screen title row: large display type, optional back arrow, actions on the right. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    large: Boolean = onBack == null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(
            title,
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** Borderless search field on a raised pill, the way search reads on a remote-first TV UI. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Night)
            .border(1.dp, Palette.LineSoft, RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = Palette.Muted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = Palette.Faint, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text),
                cursorBrush = SolidColor(Palette.Tally),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            )
        }
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, "Clear search", tint = Palette.Muted) }
    }
}

/** Filter control: quiet when off, outlined in amber with an amber label when filtering. */
@Composable
fun FilterPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    dropdown: Boolean = true,
    onClear: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.height(36.dp).clip(shape)
            .background(if (active) Palette.TallyDim else Palette.Night)
            .border(1.dp, if (active) Palette.Tally.copy(alpha = 0.55f) else Palette.LineSoft, shape)
            .clickable(onClick = onClick)
            .semantics { role = Role.Checkbox; selected = active }
            .padding(start = if (leading != null) 10.dp else 14.dp, end = if (dropdown || onClear != null) 6.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, null, tint = if (active) Palette.Tally else Palette.Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) Palette.TallyText else Palette.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp))
        when {
            onClear != null -> Icon(Icons.Default.Clear, "Clear $label", tint = Palette.Tally,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClear).padding(6.dp))
            dropdown -> Icon(Icons.Default.KeyboardArrowDown, null, tint = if (active) Palette.Tally else Palette.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Category tabs: words, not chips. The selected one gets an amber underline;
 * counts sit beside each word in muted figures.
 */
@Composable
fun TextTabs(
    tabs: List<Triple<String?, String, Int?>>,   // key (null = all), label, count
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(tabs, key = { it.first ?: "__all" }) { (key, label, count) ->
            val on = key == selected
            val underline by animateDpAsState(if (on) 3.dp else 0.dp, label = "tab")
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(key) }
                    .semantics { role = Role.Tab; this.selected = on }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(label, style = MaterialTheme.typography.titleSmall, color = if (on) Palette.Text else Palette.Muted)
                    if (count != null) {
                        Text("%,d".format(count), Modifier.padding(start = 4.dp, bottom = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = if (on) Palette.Tally else Palette.Faint)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Box(Modifier.width(22.dp).height(underline).clip(RoundedCornerShape(2.dp)).background(Palette.Tally))
            }
        }
    }
}

/** Channel logo on a tile; falls back to initials while loading or when there is no logo. */
@Composable
fun ChannelLogo(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier.size(size).clip(RoundedCornerShape(size / 4.5f)).background(Palette.Dusk),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(initials(name), style = if (size > 56.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                color = Palette.Muted)
        }
        if (url != null) {
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(size / 9),
                onState = { loaded = it is AsyncImagePainter.State.Success },
            )
        }
    }
}

fun initials(name: String): String {
    val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    return words.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "TV" }
}

/** Amber progress through the current programme. */
@Composable
fun ProgressLine(p: Programme, now: Long, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val f = ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(Palette.Haze)) {
        Box(Modifier.fillMaxWidth(f).height(height).clip(RoundedCornerShape(height)).background(Palette.Tally))
    }
}

/** Small code tag (EN, UK) for a channel's language or country. */
@Composable
fun CodeTag(code: String, emphasised: Boolean = false) {
    Text(
        if (code.isEmpty()) "?" else code.uppercase(),
        Modifier.clip(RoundedCornerShape(5.dp))
            .background(if (emphasised) Color(0xFF2F2945) else Palette.Dusk)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasised) Color(0xFFDCD5F5) else Palette.Muted,
    )
}

/** Muted sentence-case heading for a block of content. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = Palette.Muted, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Empty or failure state: say what happened and offer the next step. */
@Composable
fun Message(title: String, body: String? = null, action: String? = null, onAction: () -> Unit = {}, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (body != null) Text(body, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        if (action != null) {
            Text(
                action,
                Modifier.padding(top = 16.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Tally)
                    .clickable(onClick = onAction).padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge, color = Palette.OnTally,
            )
        }
    }
}

/** Placeholder rows shown while the first playlist load runs. */
@Composable
fun SkeletonRows(count: Int = 8) {
    Column {
        repeat(count) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Night))
                Column(Modifier.padding(start = 14.dp)) {
                    Box(Modifier.width(140.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Night))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(210.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Night))
                }
            }
        }
    }
}

/** Primary action button in amber. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(if (enabled) Palette.Tally else Palette.Dusk)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = if (enabled) Palette.OnTally else Palette.Faint)
    }
}
