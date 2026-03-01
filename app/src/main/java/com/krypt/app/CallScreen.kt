package com.krypt.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    viewModel: KryptViewModel,
    remoteUuid: String,
    onEndCall: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val callState = uiState.callState
    val contact = uiState.contacts.find { it.uuid == remoteUuid }
    val displayName = contact?.nickname?.ifBlank { remoteUuid.take(12) } ?: remoteUuid.take(12)
    val displayInitial = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var callDuration by remember { mutableStateOf(0L) }

    // Timer — only runs when connected (not incoming)
    val isConnected = callState.isInCall && !callState.isIncoming && callState.pendingOfferSdp.isEmpty()
    LaunchedEffect(isConnected) {
        if (isConnected) {
            while (true) { delay(1000); callDuration++ }
        }
    }

    // Pulse rings animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val ring1 by infiniteTransition.animateFloat(1f, 1.6f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart), "r1")
    val ring2 by infiniteTransition.animateFloat(1f, 1.6f,
        infiniteRepeatable(tween(1800, 600, easing = FastOutSlowInEasing), RepeatMode.Restart), "r2")
    val ring3 by infiniteTransition.animateFloat(1f, 1.6f,
        infiniteRepeatable(tween(1800, 1200, easing = FastOutSlowInEasing), RepeatMode.Restart), "r3")
    val ringAlpha by infiniteTransition.animateFloat(0.4f, 0f,
        infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), "ra")

    Box(
        modifier = Modifier.fillMaxSize().background(KryptTheme.bg),
        contentAlignment = Alignment.Center
    ) {
        // ─── Incoming call overlay ─────────────────────────────────────────
        if (callState.isIncoming && callState.pendingOfferSdp.isNotEmpty()) {
            IncomingCallUI(
                displayName = displayName,
                displayInitial = displayInitial,
                onDecline = onEndCall,
                onAccept = { viewModel.acceptCall() }
            )
        } else {
            // ─── Active / Outgoing call UI ─────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                Spacer(Modifier.weight(1f))

                // Status text
                Text(
                    text = when {
                        callDuration > 0 -> formatDuration(callDuration)
                        else -> "Calling…"
                    },
                    fontSize = 13.sp,
                    color = KryptTheme.subtext,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(40.dp))

                // Avatar with animated rings
                Box(contentAlignment = Alignment.Center) {
                    // Rings (visible only while connecting)
                    if (callDuration == 0L) {
                        listOf(ring1 to ringAlpha, ring2 to ringAlpha * 0.7f, ring3 to ringAlpha * 0.4f).forEach { (scale, alpha) ->
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .scale(scale)
                                    .clip(CircleShape)
                                    .border(1.dp, KryptTheme.text.copy(alpha = alpha), CircleShape)
                            )
                        }
                    }
                    // Avatar circle
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(KryptTheme.card),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayInitial,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Light,
                            color = KryptTheme.text
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))

                Text(displayName, fontSize = 26.sp, fontWeight = FontWeight.Light, color = KryptTheme.text)
                Spacer(Modifier.height(6.dp))

                // E2EE badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(KryptTheme.card)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Lock, null, tint = KryptTheme.subtext, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Encrypted call", fontSize = 11.sp, color = KryptTheme.subtext, letterSpacing = 0.5.sp)
                }

                Spacer(Modifier.weight(1f))

                // Controls row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .padding(bottom = 64.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute button
                    CallControlButton(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (isMuted) "Unmute" else "Mute",
                        active = isMuted
                    ) {
                        isMuted = !isMuted
                        viewModel.webRTCManager?.toggleMute(isMuted)
                    }

                    // End call button — large red
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = onEndCall,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(KryptTheme.danger)
                        ) {
                            Icon(Icons.Default.CallEnd, "End Call", tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("End", fontSize = 11.sp, color = KryptTheme.subtext)
                    }

                    // Speaker button
                    CallControlButton(
                        icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        label = if (isSpeakerOn) "Speaker" else "Earpiece",
                        active = isSpeakerOn
                    ) {
                        isSpeakerOn = !isSpeakerOn
                    }
                }
            }
        }
    }
}

@Composable
private fun IncomingCallUI(
    displayName: String,
    displayInitial: String,
    onDecline: () -> Unit,
    onAccept: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Spacer(Modifier.weight(1f))

        Text("Incoming Call", fontSize = 13.sp, color = KryptTheme.subtext, letterSpacing = 1.sp)
        Spacer(Modifier.height(40.dp))

        Box(
            modifier = Modifier.size(120.dp).clip(CircleShape).background(KryptTheme.card),
            contentAlignment = Alignment.Center
        ) {
            Text(displayInitial, fontSize = 44.sp, fontWeight = FontWeight.Light, color = KryptTheme.text)
        }

        Spacer(Modifier.height(28.dp))
        Text(displayName, fontSize = 26.sp, fontWeight = FontWeight.Light, color = KryptTheme.text)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(KryptTheme.card)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.Lock, null, tint = KryptTheme.subtext, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(4.dp))
            Text("Encrypted call", fontSize = 11.sp, color = KryptTheme.subtext)
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 60.dp).padding(bottom = 72.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Decline
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onDecline,
                    modifier = Modifier.size(68.dp).clip(CircleShape).background(KryptTheme.danger)
                ) {
                    Icon(Icons.Default.CallEnd, "Decline", tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("Decline", fontSize = 11.sp, color = KryptTheme.subtext)
            }
            // Accept
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onAccept,
                    modifier = Modifier.size(68.dp).clip(CircleShape).background(KryptTheme.success)
                ) {
                    Icon(Icons.Default.Call, "Accept", tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("Accept", fontSize = 11.sp, color = KryptTheme.subtext)
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (active) KryptTheme.text.copy(alpha = 0.12f) else KryptTheme.card)
        ) {
            Icon(icon, null, tint = if (active) KryptTheme.text else KryptTheme.subtext,
                modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 10.sp, color = KryptTheme.subtext)
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
