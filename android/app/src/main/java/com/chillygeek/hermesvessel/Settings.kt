package com.chillygeek.hermesvessel

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Settings: the paired Hermes gateways (pick one, add, remove) and Hermes's voice (with samples).
 * Drawn in the app's own window (not a dialog) as a bottom sheet: tap the scrim,
 * swipe the handle down, or go back to close.
 */
@Composable
fun SettingsSheet(
  visible: Boolean,
  running: Boolean,
  appVersion: String,
  onSelectGateway: (String) -> Unit,
  onRemoveGateway: (String) -> Unit,
  onAddGateway: (String) -> Boolean,
  onSelectVoice: (String) -> Unit,
  onPreviewVoice: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  BackHandler(enabled = visible, onBack = onDismiss)
  AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
    Box(
      Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.6f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close settings", onClick = onDismiss),
    )
  }
  AnimatedVisibility(
    visible,
    enter = slideInVertically { it } + fadeIn(),
    exit = slideOutVertically { it } + fadeOut(),
    modifier = Modifier.fillMaxSize(),
  ) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
      SheetBody(running, appVersion, onSelectGateway, onRemoveGateway, onAddGateway, onSelectVoice, onPreviewVoice, onDismiss)
    }
  }
}

@Composable
private fun SheetBody(
  running: Boolean,
  appVersion: String,
  onSelectGateway: (String) -> Unit,
  onRemoveGateway: (String) -> Unit,
  onAddGateway: (String) -> Boolean,
  onSelectVoice: (String) -> Unit,
  onPreviewVoice: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val voices by TalkState.voices.collectAsState()
  val selected by TalkState.selectedVoice.collectAsState()
  val previewing by TalkState.previewing.collectAsState()
  val loading by TalkState.previewLoading.collectAsState()
  val previewError by TalkState.previewError.collectAsState()
  val gateways by TalkState.gateways.collectAsState()
  val activeUrl by TalkState.activeGateway.collectAsState()
  val statuses by TalkState.gatewayStatus.collectAsState()
  var adding by remember { mutableStateOf(false) }
  var removing by remember { mutableStateOf<Gateway?>(null) }
  var drag by remember { mutableStateOf(0f) }
  val dismissAt = with(LocalDensity.current) { 120.dp.toPx() }
  val settle by animateFloatAsState(drag, label = "sheetDrag")
  val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

  Column(
    Modifier
      .fillMaxWidth()
      .fillMaxHeight(0.9f)
      .offset { IntOffset(0, settle.roundToInt()) }
      .clip(shape)
      .background(Panel)
      .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
      // Swallow taps on the sheet itself so they don't reach the scrim (no click semantics for TalkBack).
      .pointerInput(Unit) { detectTapGestures { } },
  ) {
    // Handle + title: drag down to close.
    Column(
      Modifier
        .fillMaxWidth()
        .draggable(
          orientation = Orientation.Vertical,
          state = rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f) },
          onDragStopped = { if (drag > dismissAt) onDismiss() else drag = 0f },
        )
        .padding(horizontal = 24.dp),
    ) {
      Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Muted.copy(alpha = 0.45f)))
      }
      Text("Settings", style = Type.headline)
      Spacer(Modifier.height(18.dp))
    }
    Column(
      Modifier
        .fillMaxWidth()
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(horizontal = 24.dp),
    ) {
      Text("HERMES GATEWAYS", style = Type.overline)
      Spacer(Modifier.height(6.dp))
      Text(
        when {
          running -> "End the call to switch gateways."
          gateways.size > 1 -> "Calls go to the checked gateway. Tap another to switch."
          else -> "Pair another Hermes to switch between them here."
        },
        style = Type.label,
      )
      Spacer(Modifier.height(14.dp))
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (gateway in gateways) {
          GatewayRow(
            gateway = gateway,
            isActive = gateway.url == (activeUrl ?: gateways.firstOrNull()?.url),
            status = statuses[gateway.url] ?: GatewayStatus.CHECKING,
            enabled = !running,
            onSelect = { onSelectGateway(gateway.url) },
            onRemove = { removing = gateway },
          )
        }
      }
      Spacer(Modifier.height(6.dp))
      if (adding) {
        Spacer(Modifier.height(8.dp))
        Text(
          buildAnnotatedString {
            append("On the other computer, run ")
            withStyle(Type.code.toSpanStyle()) { append("hermes vessel pair") }
            append(" and scan its QR code with your camera, or paste its link here.")
          },
          style = Type.label,
        )
        Spacer(Modifier.height(12.dp))
        PairLinkField(onPairLink = onAddGateway, buttonLabel = "Add gateway", onPaired = { adding = false })
        TextButton(onClick = { adding = false }, contentPadding = PaddingValues(vertical = 10.dp)) {
          Text("Cancel", color = Muted)
        }
      } else {
        TextButton(onClick = { adding = true }, contentPadding = PaddingValues(vertical = 10.dp)) {
          Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = GoldLight, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Add a gateway", color = GoldLight)
        }
      }

      Spacer(Modifier.height(26.dp))
      ScreenPermissionSettings(activeUrl)
      Spacer(Modifier.height(26.dp))
      Text("VOICE", style = Type.overline)
      Spacer(Modifier.height(6.dp))
      Text(
        if (running) "Your next call will use the voice you pick." else "Tap play to hear a voice. The first listen takes a few seconds.",
        style = Type.label,
      )
      if (previewError.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(previewError, style = Type.label.copy(color = Coral))
      }
      Spacer(Modifier.height(14.dp))
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (voice in voices) {
          VoiceRow(
            voice = voice,
            isSelected = voice.id == selected,
            isPlaying = voice.id == previewing,
            isLoading = loading && voice.id == previewing,
            previewEnabled = !running,
            onSelect = { onSelectVoice(voice.id) },
            onPreview = { onPreviewVoice(voice.id) },
          )
        }
      }

      Spacer(Modifier.height(30.dp))
      Spacer(Modifier.height(18.dp))
      Text("Hermes Vessel $appVersion", style = Type.label.copy(color = Muted.copy(alpha = 0.7f)))
      Spacer(Modifier.height(28.dp))
    }
  }

  removing?.let { gateway ->
    val isActive = gateway.url == (activeUrl ?: gateways.firstOrNull()?.url)
    val onlyOne = gateways.size <= 1
    AlertDialog(
      onDismissRequest = { removing = null },
      containerColor = Panel,
      title = { Text("Remove ${gateway.name}?", style = Type.headline.copy(fontSize = 24.sp)) },
      text = {
        Text(
          buildString {
            append("This phone will forget ${gateway.path}. You can pair it again from its link or QR code.")
            if (running && isActive) append(" This ends the current call.")
            if (onlyOne) append(" It's your only gateway, so the app will go back to pairing.")
          },
          style = Type.body,
        )
      },
      confirmButton = {
        TextButton(onClick = {
          removing = null
          onRemoveGateway(gateway.url)
        }) { Text("Remove", color = Coral) }
      },
      dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep", color = Ivory) } },
    )
  }
}

@Composable
private fun GatewayRow(
  gateway: Gateway,
  isActive: Boolean,
  status: GatewayStatus,
  enabled: Boolean,
  onSelect: () -> Unit,
  onRemove: () -> Unit,
) {
  val shape = RoundedCornerShape(18.dp)
  val border by animateColorAsState(if (isActive) Gold.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.07f), label = "gatewayBorder")
  val fill by animateColorAsState(if (isActive) Gold.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.02f), label = "gatewayFill")
  val dot = when (status) {
    GatewayStatus.ONLINE -> Jade
    GatewayStatus.OFFLINE, GatewayStatus.REJECTED -> Coral
    GatewayStatus.CHECKING -> Muted
  }
  val statusText = when (status) {
    GatewayStatus.ONLINE -> "Online"
    GatewayStatus.OFFLINE -> "Not reachable right now"
    GatewayStatus.REJECTED -> "Doesn't accept this phone anymore. Pair it again."
    GatewayStatus.CHECKING -> "Checking…"
  }
  Row(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(fill)
      .border(1.dp, border, shape)
      .clickable(enabled = enabled && !isActive, role = Role.RadioButton, onClickLabel = "Use ${gateway.name}", onClick = onSelect)
      .semantics { selected = isActive }
      .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          gateway.name,
          style = Type.hermes.copy(fontSize = 20.sp, fontWeight = FontWeight(400)),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (isActive) {
          Spacer(Modifier.width(8.dp))
          Icon(painterResource(R.drawable.ic_check), contentDescription = "Active", tint = GoldLight, modifier = Modifier.size(18.dp))
        }
      }
      Text(gateway.path, style = Type.label.copy(color = Ivory.copy(alpha = 0.78f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(statusText, style = Type.label.copy(color = if (dot == Coral) Coral else Muted))
    }
    Box(
      Modifier
        .size(44.dp)
        .clip(CircleShape)
        .clickable(role = Role.Button, onClickLabel = "Remove ${gateway.name}", onClick = onRemove),
      contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove", tint = Muted, modifier = Modifier.size(18.dp))
    }
  }
}

@Composable
private fun VoiceRow(
  voice: Voice,
  isSelected: Boolean,
  isPlaying: Boolean,
  isLoading: Boolean,
  previewEnabled: Boolean,
  onSelect: () -> Unit,
  onPreview: () -> Unit,
) {
  val shape = RoundedCornerShape(18.dp)
  val border by animateColorAsState(if (isSelected) Gold.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.07f), label = "voiceBorder")
  val fill by animateColorAsState(if (isSelected) Gold.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.02f), label = "voiceFill")
  Row(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(fill)
      .border(1.dp, border, shape)
      .clickable(role = Role.RadioButton, onClickLabel = "Use ${voice.name}", onClick = onSelect)
      .semantics { selected = isSelected }
      .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(voice.name, style = Type.hermes.copy(fontSize = 20.sp, fontWeight = FontWeight(400)))
        if (isSelected) {
          Spacer(Modifier.width(8.dp))
          Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected", tint = GoldLight, modifier = Modifier.size(18.dp))
        }
      }
      Text("${voice.note} · ${voice.tone}", style = Type.label)
    }
    Box(
      Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(if (isPlaying) Gold.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f))
        .clickable(
          enabled = previewEnabled,
          role = Role.Button,
          onClickLabel = if (isPlaying) "Stop sample" else "Play a sample of ${voice.name}",
          onClick = onPreview,
        ),
      contentAlignment = Alignment.Center,
    ) {
      when {
        isLoading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = GoldLight)
        isPlaying -> Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop", tint = GoldLight, modifier = Modifier.size(20.dp))
        else -> Icon(
          painterResource(R.drawable.ic_play),
          contentDescription = "Play",
          tint = if (previewEnabled) Ivory else Muted.copy(alpha = 0.4f),
          modifier = Modifier.size(22.dp),
        )
      }
    }
  }
}
