package com.chillygeek.hermesvessel

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Fade the top of a region to transparent, so clipped captions dissolve instead of being cut. */
private fun Modifier.fadingTopEdge(): Modifier = this
  .clipToBounds()
  .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
  .drawWithContent {
    drawContent()
    drawRect(
      Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black, 1f to Color.Black),
      blendMode = BlendMode.DstIn,
    )
  }

/** What the screen shows, combining the server's state with what the phone itself knows. */
fun visibleState(status: String, running: Boolean, playing: Boolean, muted: Boolean): String = when {
  status == "error" -> "error"
  !running -> if (status == "connecting") "connecting" else "idle"
  playing -> "speaking"
  status == "thinking" -> "thinking"
  status == "connecting" -> "connecting"
  muted -> "muted"
  else -> "listening"
}

@Composable
fun VesselApp(
  host: String?,
  showSettings: Boolean,
  appVersion: String,
  onTalk: () -> Unit,
  onEnd: () -> Unit,
  onMute: () -> Unit,
  onInterrupt: () -> Unit,
  onPairLink: (String) -> Boolean,
  onSelectGateway: (String) -> Unit,
  onRemoveGateway: (String) -> Unit,
  onOpenSettings: () -> Unit,
  onCloseSettings: () -> Unit,
  onSelectVoice: (String) -> Unit,
  onPreviewVoice: (String) -> Unit,
) {
  val status by TalkState.status.collectAsState()
  val running by TalkState.running.collectAsState()
  val muted by TalkState.muted.collectAsState()
  val playing by TalkState.playing.collectAsState()
  val mic by TalkState.micLevel.collectAsState()
  val out by TalkState.outLevel.collectAsState()

  val state = if (host == null) "idle" else visibleState(status, running, playing, muted)
  val level = when (state) {
    "speaking" -> out
    "listening" -> mic
    else -> 0f
  }
  val palette = OrbPalette.of(state)
  val ambient by animateColorAsState(palette.primary, tween(900), label = "ambient")
  val ambientEnergy by animateFloatAsState(palette.energy, tween(900), label = "ambientEnergy")

  Box(Modifier.fillMaxSize().background(Ink)) {
    // The light in the vessel lights the room.
    Canvas(Modifier.fillMaxSize()) {
      drawRect(Brush.verticalGradient(listOf(InkTop, Ink)))
      val c = Offset(size.width / 2f, size.height * 0.38f)
      val reach = size.width * 1.05f
      drawCircle(
        Brush.radialGradient(
          0f to ambient.copy(alpha = 0.17f * ambientEnergy),
          1f to Color.Transparent,
          center = c,
          radius = reach,
        ),
        radius = reach,
        center = c,
      )
    }
    if (host == null) {
      PairScreen(onPairLink)
    } else {
      CallScreen(host, state, level, running, muted, onTalk, onEnd, onMute, onInterrupt, onOpenSettings)
      SettingsSheet(
        visible = showSettings,
        running = running,
        appVersion = appVersion,
        onSelectGateway = onSelectGateway,
        onRemoveGateway = onRemoveGateway,
        onAddGateway = onPairLink,
        onSelectVoice = onSelectVoice,
        onPreviewVoice = onPreviewVoice,
        onDismiss = onCloseSettings,
      )
    }
  }
}

@Composable
private fun CallScreen(
  host: String,
  state: String,
  level: Float,
  running: Boolean,
  muted: Boolean,
  onTalk: () -> Unit,
  onEnd: () -> Unit,
  onMute: () -> Unit,
  onInterrupt: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val online by TalkState.hostOnline.collectAsState()
  val detail by TalkState.detail.collectAsState()
  val route by TalkState.route.collectAsState()
  val since by TalkState.thinkingSince.collectAsState()
  val lines by TalkState.lines.collectAsState()
  val live by TalkState.liveCaption.collectAsState()
  val lastCall by TalkState.lastCall.collectAsState()

  val elapsed by produceState(0L, since) {
    value = 0L
    while (since > 0L) {
      value = (System.currentTimeMillis() - since) / 1000
      delay(500)
    }
  }
  LaunchedEffect(lastCall) {
    if (lastCall.isNotEmpty()) {
      delay(6000)
      TalkState.lastCall.value = ""
    }
  }

  val screenHeight = LocalConfiguration.current.screenHeightDp
  val orbSize = (screenHeight * 0.30f).coerceIn(180f, 290f).dp

  // The orb and title never move; captions take whatever space is left, newest words at the bottom.
  Column(
    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Header(host = host, online = online, onSettings = onOpenSettings)
    Spacer(Modifier.height((screenHeight * 0.05f).dp))

    val orbAction = when {
      state == "speaking" -> "Interrupt Hermes"
      !running -> "Talk to Hermes"
      else -> null
    }
    Orb(
      state = state,
      level = level,
      modifier = Modifier
        .size(orbSize)
        .clickable(
          enabled = orbAction != null,
          onClickLabel = orbAction,
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
        ) { if (state == "speaking") onInterrupt() else onTalk() }
        .semantics { contentDescription = "Hermes: ${title(state, online)}" },
    )

    Spacer(Modifier.height(30.dp))
    AnimatedContent(
      targetState = title(state, online),
      transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
      label = "title",
    ) { text ->
      Text(text, style = Type.display, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
    }
    Spacer(Modifier.height(8.dp))
    Text(
      subtitle(state, online, detail, route, elapsed, lastCall),
      style = Type.body,
      textAlign = TextAlign.Center,
      // Errors can carry a "retry after…" time the user needs to read, so let them wrap fully.
      maxLines = if (state == "error") 8 else 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.widthIn(max = 340.dp),
    )
    if (state == "thinking") {
      Spacer(Modifier.height(12.dp))
      Text(
        "%d:%02d".format(elapsed / 60, elapsed % 60),
        style = Type.code.copy(color = Ivory.copy(alpha = 0.7f)),
        modifier = Modifier
          .clip(RoundedCornerShape(50))
          .background(Color.White.copy(alpha = 0.06f))
          .padding(horizontal = 12.dp, vertical = 4.dp),
      )
    }

    Box(
      Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp).fadingTopEdge(),
      contentAlignment = Alignment.BottomStart,
    ) {
      val captionAlpha by animateFloatAsState(if (running || lines.isNotEmpty()) 1f else 0f, tween(400), label = "captions")
      Box(Modifier.graphicsLayer { alpha = captionAlpha }) {
        Captions(lines, live)
      }
    }
    Spacer(Modifier.height(20.dp))
    if (running) {
      CallControls(muted = muted, onMute = onMute, onEnd = onEnd)
    } else {
      PrimaryButton(if (state == "error") "Try again" else "Talk to Hermes", enabled = true, onClick = onTalk)
    }
    Spacer(Modifier.height(16.dp))
  }

}

private fun title(state: String, online: Boolean? = null): String = when (state) {
  "idle" -> when (online) {
    true -> "Hermes is here"
    false -> "Hermes is away"
    null -> "Looking for Hermes"
  }
  "connecting" -> "Connecting"
  "listening" -> "Listening"
  "muted" -> "Muted"
  "thinking" -> "Working on it"
  "speaking" -> "Speaking"
  "error" -> "Something went wrong"
  else -> ""
}

private fun subtitle(state: String, online: Boolean?, detail: String, route: String, elapsed: Long, lastCall: String): String =
  when (state) {
    "idle" -> when {
      lastCall.isNotEmpty() -> lastCall
      online == false -> "Can't reach it right now. Check that the computer is on."
      online == true -> "Tap the light to talk"
      else -> "Checking the connection…"
    }
    "connecting" -> "Opening a voice line"
    "listening" -> route.ifBlank { "Go ahead" }
    "muted" -> "Hermes can't hear you"
    "thinking" -> detail.ifBlank { "Hermes is on it" }
    "speaking" -> "Tap the light to interrupt"
    "error" -> detail.ifBlank { "Please try again" }
    else -> ""
  }

@Composable
private fun Header(host: String?, online: Boolean?, onSettings: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    // "Hermes" is who you talk to; "Vessel" (lighter) is the app that gives it a voice.
    Text(
      buildAnnotatedString {
        append("Hermes")
        withStyle(SpanStyle(fontWeight = FontWeight(300), color = Ivory.copy(alpha = 0.62f))) { append(" Vessel") }
      },
      style = Type.wordmark,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).semantics { contentDescription = "Hermes Vessel" },
    )
    if (host != null) {
      Spacer(Modifier.width(12.dp))
      HostChip(host, online, onSettings)
      Spacer(Modifier.width(8.dp))
      Box(
        Modifier
          .size(36.dp)
          .clip(CircleShape)
          .background(Color.White.copy(alpha = 0.05f))
          .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
          .clickable(role = Role.Button, onClickLabel = "Settings", onClick = onSettings),
        contentAlignment = Alignment.Center,
      ) {
        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = Ivory.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
      }
    }
  }
}

@Composable
private fun HostChip(host: String, online: Boolean?, onClick: () -> Unit) {
  val dot by animateColorAsState(
    when (online) {
      true -> Jade
      false -> Coral
      null -> Muted
    },
    label = "dot",
  )
  val shape = RoundedCornerShape(50)
  Row(
    Modifier
      .clip(shape)
      .background(Color.White.copy(alpha = 0.05f))
      .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
      .clickable(onClickLabel = "Settings", onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
    Spacer(Modifier.width(8.dp))
    Text(
      host,
      style = Type.label.copy(color = Ivory.copy(alpha = 0.85f)),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.widthIn(max = 132.dp),
    )
  }
}

@Composable
private fun Captions(lines: List<Pair<String, String>>, live: String) {
  val lastUser = lines.lastOrNull { it.first == "user" }?.second.orEmpty()
  val hermes = live.ifBlank { lines.lastOrNull { it.first == "assistant" }?.second.orEmpty() }
  // While Hermes is mid-sentence keep the newest words in view.
  val shown = if (hermes.length > 220) "…" + hermes.takeLast(220).substringAfter(' ') else hermes
  Column(Modifier.fillMaxWidth().animateContentSize()) {
    if (lastUser.isNotEmpty()) {
      Text("YOU", style = Type.overline)
      Spacer(Modifier.height(4.dp))
      Text(lastUser, style = Type.user, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Spacer(Modifier.height(16.dp))
    }
    if (shown.isNotEmpty()) {
      Text("HERMES", style = Type.overline.copy(color = Gold.copy(alpha = 0.85f)))
      Spacer(Modifier.height(4.dp))
      Text(shown, style = Type.hermes, maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun CallControls(muted: Boolean, onMute: () -> Unit, onEnd: () -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.Top) {
    RoundControl(
      icon = if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
      label = if (muted) "Unmute" else "Mute",
      background = if (muted) Ivory else Color.White.copy(alpha = 0.08f),
      tint = if (muted) Ink else Ivory,
      onClick = onMute,
    )
    RoundControl(icon = R.drawable.ic_call_end, label = "End", background = EndRed, tint = Color.White, onClick = onEnd)
  }
}

@Composable
private fun RoundControl(@DrawableRes icon: Int, label: String, background: Color, tint: Color, onClick: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "press")
  val bg by animateColorAsState(background, label = "controlBg")
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier
        .size(72.dp)
        .scale(scale)
        .clip(CircleShape)
        .background(bg)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
      contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(28.dp))
    }
    Spacer(Modifier.height(8.dp))
    Text(label, style = Type.label)
  }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
  val shape = RoundedCornerShape(30.dp)
  val base = Modifier
    .fillMaxWidth()
    .widthIn(max = 420.dp)
    .height(60.dp)
    .scale(scale)
    .clip(shape)
  val look = if (enabled) {
    base.background(Brush.horizontalGradient(listOf(GoldLight, Gold)))
  } else {
    base.border(1.dp, Gold.copy(alpha = 0.35f), shape)
  }
  Box(
    look.clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, style = Type.button.copy(color = if (enabled) Ink else Gold.copy(alpha = 0.55f)))
  }
}

@Composable
private fun PairScreen(onPairLink: (String) -> Boolean) {
  Column(
    Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Header(host = null, online = null, onSettings = {})
    Spacer(Modifier.height(28.dp))
    Orb(state = "idle", level = 0f, modifier = Modifier.size(170.dp))
    Spacer(Modifier.height(28.dp))
    Text("Give Hermes a body", style = Type.headline, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(10.dp))
    Text(
      "Pair this phone with your Hermes and it becomes a voice you can carry.",
      style = Type.body,
      textAlign = TextAlign.Center,
      modifier = Modifier.widthIn(max = 340.dp),
    )
    Spacer(Modifier.height(30.dp))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Step(1, "On the computer running Hermes, run", code = "hermes vessel pair")
      Step(2, "Open the link it prints on this phone.")
      Step(3, "Tap Pair this phone.")
    }
    Spacer(Modifier.height(30.dp))
    Text("OR PASTE THE PAIRING LINK", style = Type.overline, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    PairLinkField(onPairLink)
    Spacer(Modifier.height(24.dp))
  }
}

/** Paste a pairing link: the pairing screen and Settings → Add a gateway. */
@Composable
fun PairLinkField(onPairLink: (String) -> Boolean, buttonLabel: String = "Pair", onPaired: () -> Unit = {}) {
  var link by remember { mutableStateOf("") }
  var invalid by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth()) {
    OutlinedTextField(
      value = link,
      onValueChange = {
        link = it
        invalid = false
      },
      singleLine = true,
      isError = invalid,
      placeholder = { Text("https://…/vessel/?t=…", style = Type.label) },
      shape = RoundedCornerShape(16.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Gold,
        unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
        errorBorderColor = Coral,
        focusedTextColor = Ivory,
        unfocusedTextColor = Ivory,
        cursorColor = Gold,
        focusedContainerColor = Color.White.copy(alpha = 0.03f),
        unfocusedContainerColor = Color.White.copy(alpha = 0.03f),
        errorContainerColor = Color.White.copy(alpha = 0.03f),
      ),
      modifier = Modifier.fillMaxWidth(),
    )
    if (invalid) {
      Spacer(Modifier.height(6.dp))
      Text("That doesn't look like a Vessel pairing link.", style = Type.label.copy(color = Coral), modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(buttonLabel, enabled = link.isNotBlank()) {
      if (onPairLink(link)) {
        link = ""
        invalid = false
        onPaired()
      } else {
        invalid = true
      }
    }
  }
}

@Composable
private fun Step(number: Int, text: String, code: String? = null) {
  Row(verticalAlignment = Alignment.Top) {
    Box(
      Modifier.size(26.dp).clip(CircleShape).border(1.dp, Gold.copy(alpha = 0.6f), CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Text("$number", style = Type.label.copy(color = GoldLight))
    }
    Spacer(Modifier.width(14.dp))
    Column(Modifier.padding(top = 3.dp)) {
      Text(text, style = Type.body.copy(color = Ivory.copy(alpha = 0.85f)))
      if (code != null) {
        Spacer(Modifier.height(6.dp))
        Text(
          code,
          style = Type.code,
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        )
      }
    }
  }
}
