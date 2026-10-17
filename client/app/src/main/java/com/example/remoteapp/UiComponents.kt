
package com.example.remoteapp

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LiveStatusBadge(
    text: String = "ONLINE",
    color: Color = AppTheme.AccentEmerald,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "livePulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveScale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveAlpha"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .scale(scale)
                .alpha(alpha)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accentColor: Color = AppTheme.AccentCyan,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = AppTheme.TextPrimary,
            letterSpacing = 1.sp
        )
        if (action != null) {
            Spacer(Modifier.weight(1f))
            action()
        }
    }
}

@Composable
fun ManualIpConnectDialog(
    initialIp: String = "",
    onDismiss: () -> Unit,
    onConnect: (name: String, ipAndPort: String) -> Unit
) {
    var nameInput by remember { mutableStateOf("My PC") }
    var ipInput by remember { mutableStateOf(initialIp.ifBlank { "192.168.1." }) }
    var portInput by remember { mutableStateOf("5055") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.CardDarkElevated,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(AppTheme.AccentCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Lan,
                        contentDescription = null,
                        tint = AppTheme.AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Direct IP Connect",
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.TextPrimary,
                        fontSize = 18.sp
                    )
                    Text(
                        "Connect directly without auto-discovery",
                        fontSize = 11.sp,
                        color = AppTheme.TextSecondary
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("PC Label / Name", fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentCyan,
                        unfocusedBorderColor = AppTheme.CardBorderSubtle,
                        focusedTextColor = AppTheme.TextPrimary,
                        unfocusedTextColor = AppTheme.TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("IP Address", fontSize = 12.sp) },
                        placeholder = { Text("192.168.1.X") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.AccentCyan,
                            unfocusedBorderColor = AppTheme.CardBorderSubtle,
                            focusedTextColor = AppTheme.TextPrimary,
                            unfocusedTextColor = AppTheme.TextPrimary
                        ),
                        modifier = Modifier.weight(2f)
                    )

                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it },
                        label = { Text("Port", fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.AccentCyan,
                            unfocusedBorderColor = AppTheme.CardBorderSubtle,
                            focusedTextColor = AppTheme.TextPrimary,
                            unfocusedTextColor = AppTheme.TextPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanIp = ipInput.trim()
                    val cleanPort = portInput.trim().ifEmpty { "5055" }
                    val finalTarget = "$cleanIp:$cleanPort"
                    val finalName = nameInput.trim().ifEmpty { "Remote PC" }
                    onConnect(finalName, finalTarget)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentCyan),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Connect", color = AppTheme.BackgroundDark, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppTheme.TextSecondary)
            }
        }
    )
}
