package com.krypt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.delay

// ─── Color System ─────────────────────────────────────────────────────────────
val D_BG      = Color(0xFF080808)
val D_SURFACE = Color(0xFF101010)
val D_CARD    = Color(0xFF181818)
val D_DIVIDER = Color(0xFF242424)
val D_TEXT    = Color(0xFFEEEEEE)
val D_SUBTEXT = Color(0xFF666666)

val L_BG      = Color(0xFFF8F8F8)
val L_SURFACE = Color(0xFFFFFFFF)
val L_CARD    = Color(0xFFF0F0F0)
val L_DIVIDER = Color(0xFFE2E2E2)
val L_TEXT    = Color(0xFF0E0E0E)
val L_SUBTEXT = Color(0xFF888888)

object KryptTheme {
    var isDark by mutableStateOf(true)
    val bg:      Color get() = if (isDark) D_BG      else L_BG
    val surface: Color get() = if (isDark) D_SURFACE else L_SURFACE
    val card:    Color get() = if (isDark) D_CARD    else L_CARD
    val divider: Color get() = if (isDark) D_DIVIDER else L_DIVIDER
    val text:    Color get() = if (isDark) D_TEXT    else L_TEXT
    val subtext: Color get() = if (isDark) D_SUBTEXT else L_SUBTEXT
    val accent:  Color get() = if (isDark) D_TEXT    else L_TEXT
    val danger:  Color = Color(0xFFCC3333)
    val success: Color = Color(0xFF3A9E5F)
}

// Legacy aliases
val KryptBlack   get() = KryptTheme.bg
val KryptDark    get() = KryptTheme.surface
val KryptCard    get() = KryptTheme.card
val KryptAccent  get() = KryptTheme.accent
val KryptText    get() = KryptTheme.text
val KryptSubtext get() = KryptTheme.subtext
val MatrixGreen  = Color(0xFF4CAF50)

private fun darkScheme() = darkColorScheme(
    primary = D_TEXT, onPrimary = D_BG, background = D_BG, onBackground = D_TEXT,
    surface = D_SURFACE, onSurface = D_TEXT, surfaceVariant = D_CARD, onSurfaceVariant = D_TEXT,
    secondary = D_TEXT, onSecondary = D_BG, outline = D_DIVIDER
)
private fun lightScheme() = lightColorScheme(
    primary = L_TEXT, onPrimary = L_BG, background = L_BG, onBackground = L_TEXT,
    surface = L_SURFACE, onSurface = L_TEXT, surfaceVariant = L_CARD, onSurfaceVariant = L_TEXT,
    secondary = L_TEXT, onSecondary = L_BG, outline = L_DIVIDER
)

class MainActivity : ComponentActivity() {
    private val viewModel: KryptViewModel by viewModels { KryptViewModel.Factory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("krypt_prefs", MODE_PRIVATE)
        KryptTheme.isDark = prefs.getBoolean("dark_mode", true)
        setContent {
            val isDark = KryptTheme.isDark
            MaterialTheme(colorScheme = if (isDark) darkScheme() else lightScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = KryptTheme.bg) {
                    KryptApp(viewModel = viewModel, onToggleTheme = {
                        KryptTheme.isDark = !KryptTheme.isDark
                        prefs.edit().putBoolean("dark_mode", KryptTheme.isDark).apply()
                    })
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NetworkClient.disconnect()
    }
}

// ─── App Root ─────────────────────────────────────────────────────────────────

@Composable
fun KryptApp(viewModel: KryptViewModel, onToggleTheme: () -> Unit) {
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(2600); showSplash = false }
    AnimatedContent(
        targetState = showSplash,
        transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(500)) },
        label = "splash"
    ) { splash ->
        if (splash) MinimalistSplash() else KryptNavGraph(viewModel, onToggleTheme)
    }
}

// ─── Designer Minimalist Splash ───────────────────────────────────────────────

@Composable
fun MinimalistSplash() {
    // Staggered fade-in
    val contentAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(1000, delayMillis = 200, easing = FastOutSlowInEasing),
        label = "a"
    )
    val taglineAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(800, delayMillis = 700, easing = FastOutSlowInEasing),
        label = "ta"
    )

    // Scanning line animation
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "sp"
    )

    Box(
        modifier = Modifier.fillMaxSize().background(D_BG),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.alpha(contentAlpha)
        ) {
            // Icon container with scanning effect
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(D_CARD)
                    .drawBehind {
                        // Scanning line
                        val y = scanProgress * size.height
                        drawLine(
                            color = D_TEXT.copy(alpha = (1f - scanProgress) * 0.15f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("🔒", fontSize = 36.sp)
            }

            Spacer(Modifier.height(32.dp))

            // App name — ultra light
            Text(
                text = "KRYPT",
                fontSize = 24.sp,
                fontWeight = FontWeight.Thin,
                fontFamily = FontFamily.Default,
                color = D_TEXT,
                letterSpacing = 12.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            // Single pixel divider
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(1.dp)
                    .background(D_DIVIDER)
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "end-to-end encrypted",
                fontSize = 10.sp,
                color = D_SUBTEXT,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.alpha(taglineAlpha)
            )
        }

        // Bottom credit
        Text(
            "by Rahul",
            color = D_SUBTEXT.copy(alpha = 0.35f),
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(taglineAlpha)
        )
    }
}

// ─── Nav Graph ─────────────────────────────────────────────────────────────────

@Composable
fun KryptNavGraph(viewModel: KryptViewModel, onToggleTheme: () -> Unit) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.callState.isInCall) {
        if (uiState.callState.isInCall) {
            navController.navigate("call/${uiState.callState.remoteUuid}") { launchSingleTop = true }
        }
    }

    NavHost(navController = navController, startDestination = "contacts") {
        composable("contacts") {
            ContactsScreen(
                viewModel = viewModel,
                onOpenChat = { uuid -> viewModel.openConversation(uuid); navController.navigate("chat/$uuid") },
                onOpenStatus = { navController.navigate("status") },
                onToggleTheme = onToggleTheme
            )
        }
        composable("chat/{uuid}", arguments = listOf(navArgument("uuid") { type = NavType.StringType })) { bs ->
            val uuid = bs.arguments?.getString("uuid") ?: return@composable
            ChatScreen(viewModel = viewModel, contactUuid = uuid,
                onStartCall = { viewModel.startCall(uuid) },
                onBack = { navController.popBackStack() })
        }
        composable("call/{uuid}", arguments = listOf(navArgument("uuid") { type = NavType.StringType })) { bs ->
            val uuid = bs.arguments?.getString("uuid") ?: return@composable
            CallScreen(viewModel = viewModel, remoteUuid = uuid, onEndCall = {
                viewModel.endCall(); navController.popBackStack()
            })
        }
        composable("status") {
            StatusScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
