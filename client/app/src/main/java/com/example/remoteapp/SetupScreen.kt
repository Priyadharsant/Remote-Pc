package com.example.remoteapp

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SetupScreen(
    onSetupComplete: (deviceName: String, password: String) -> Unit
) {
    val context = LocalContext.current
    var phoneName by remember { mutableStateOf(SecureStorage.getDeviceName(context)) }
    var password by remember { mutableStateOf(SecureStorage.getPassword(context)) }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun handleSave() {
        val name = phoneName.trim()
        val pass = password.trim()

        if (name.isEmpty()) {
            errorMessage = "Please enter a device name."
            return
        }

        errorMessage = null
        SecureStorage.saveDeviceName(context, name)
        if (pass.isNotEmpty()) {
            SecureStorage.savePassword(context, pass)
        }
        SecureStorage.setConfigured(context, true)
        Toast.makeText(context, "Setup completed!", Toast.LENGTH_SHORT).show()
        onSetupComplete(name, pass)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppTheme.BackgroundDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Hero Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(AppTheme.AccentCyan.copy(alpha = 0.25f), AppTheme.AccentSky.copy(alpha = 0.1f))
                        )
                    )
                    .border(2.dp, AppTheme.AccentCyan.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.PhoneAndroid,
                    contentDescription = null,
                    tint = AppTheme.AccentCyan,
                    modifier = Modifier.size(42.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Welcome to Remote PC",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = AppTheme.TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Quick Device Setup",
                fontSize = 13.sp,
                color = AppTheme.AccentSky,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            // Card 1: Device Name
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Badge, null, tint = AppTheme.AccentCyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("DEVICE NAME", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppTheme.AccentCyan)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = phoneName,
                        onValueChange = { phoneName = it },
                        label = { Text("Device Name") },
                        placeholder = { Text("e.g. My Phone") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.AccentCyan,
                            unfocusedBorderColor = AppTheme.CardBorderSubtle,
                            focusedTextColor = AppTheme.TextPrimary,
                            unfocusedTextColor = AppTheme.TextPrimary
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Identifies your device when connecting to your PC.",
                        fontSize = 11.sp,
                        color = AppTheme.TextSecondary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Card 2: Password (for future needs)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = AppTheme.AccentEmerald, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("APP SECURITY PASSWORD", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppTheme.AccentEmerald)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password (Optional)") },
                        placeholder = { Text("Create app password") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    tint = AppTheme.TextSecondary
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.AccentEmerald,
                            unfocusedBorderColor = AppTheme.CardBorderSubtle,
                            focusedTextColor = AppTheme.TextPrimary,
                            unfocusedTextColor = AppTheme.TextPrimary
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Stored securely in hardware vault for future security features.",
                        fontSize = 11.sp,
                        color = AppTheme.TextSecondary
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppTheme.AccentRose.copy(alpha = 0.15f)),
                    border = BorderStroke(1.dp, AppTheme.AccentRose.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = AppTheme.AccentRose, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(errorMessage!!, color = AppTheme.AccentRose, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // Continue Button
            Button(
                onClick = { handleSave() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentCyan)
            ) {
                Icon(Icons.Rounded.ArrowForward, null, modifier = Modifier.size(20.dp), tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("Get Started", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.Black)
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Rounded.VerifiedUser, null, tint = AppTheme.AccentEmerald, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Encrypted with Android KeyStore Vault",
                    fontSize = 11.sp,
                    color = AppTheme.TextMuted
                )
            }
        }
    }
}
