package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.winlator.star.store.GogDownloadManager
import com.winlator.star.store.GogGame
import com.winlator.star.store.GogGameDetailActivity
import com.winlator.star.store.GogInstallPath
import com.winlator.star.store.GogLaunchHelper
import com.winlator.star.store.GogLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.function.Consumer

// GOG dark palette (matches the original Activities)
private val GogBg = Color(0xFF0D0D0D)
private val GogCard = Color(0xFF1A1A2E)
private val GogPurple = Color(0xFF7033FF)
private val GogOrange = Color(0xFFFF9800)
private val GogPrefs = "bh_gog_prefs"

private enum class GogView { HUB, LOGIN, LIBRARY }

private fun normUrl(u: String): String =
    if (u.startsWith("//")) "https:$u" else u

/** Per-game install/download state, driven by GogDownloadManager callbacks. */
private class InstallState(installed: Boolean) {
    var installed by mutableStateOf(installed)
    var downloading by mutableStateOf(false)
    var pct by mutableStateOf(0)
    var status by mutableStateOf("")
    var cancel: Runnable? = null
}

@Composable
fun GogScreen() {
    val context = LocalContext.current
    var view by remember { mutableStateOf(GogView.HUB) }
    var loggedIn by remember { mutableStateOf(GogLibrary.isLoggedIn(context)) }

    when (view) {
        GogView.HUB -> GogHub(
            loggedIn = loggedIn,
            username = if (loggedIn) GogLibrary.username(context) else "",
            onLogin = { view = GogView.LOGIN },
            onLibrary = { view = GogView.LIBRARY },
            onSignOut = { GogLibrary.signOut(context); loggedIn = false },
        )
        GogView.LOGIN -> GogLoginWebView(
            onDone = {
                loggedIn = GogLibrary.isLoggedIn(context)
                view = GogView.HUB
            },
            onCancel = { view = GogView.HUB },
        )
        GogView.LIBRARY -> GogLibraryScreen(
            onBack = {
                loggedIn = GogLibrary.isLoggedIn(context)
                view = GogView.HUB
            },
        )
    }
}

// ── Hub ─────────────────────────────────────────────────────────────────────

@Composable
private fun GogHub(
    loggedIn: Boolean,
    username: String,
    onLogin: () -> Unit,
    onLibrary: () -> Unit,
    onSignOut: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(GogCard),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(40.dp),
        ) {
            Text("GOG.com", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (!loggedIn) {
                Text(
                    "Sign in to access your GOG game library",
                    color = Color(0xFFAAAAAA), fontSize = 14.sp,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onLogin,
                    colors = ButtonDefaults.buttonColors(containerColor = GogPurple),
                ) { Text("Login with GOG", color = Color.White) }
            } else {
                Text("Signed in as: $username", color = Color(0xFFCCCCCC), fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onLibrary,
                    colors = ButtonDefaults.buttonColors(containerColor = GogPurple),
                ) { Text("View Game Library", color = Color.White) }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onSignOut,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
                ) { Text("Sign Out", color = Color.White) }
            }
        }
    }
}

// ── Login (WebView OAuth) ────────────────────────────────────────────────────

@Composable
private fun GogLoginWebView(onDone: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    // Defined before AndroidView so the WebViewClient factory can reference it.
    fun handleRedirect(uri: Uri) {
        val fragment = uri.fragment ?: return
        val frag = Uri.parse("x://x?$fragment")
        val accessToken = frag.getQueryParameter("access_token") ?: return
        val refreshToken = frag.getQueryParameter("refresh_token")
        val userId = frag.getQueryParameter("user_id")
        working = true
        scope.launch {
            withContext(Dispatchers.IO) {
                GogLibrary.completeLogin(context, accessToken, refreshToken, userId)
            }
            onDone()
        }
    }

    Box(Modifier.fillMaxSize().background(GogBg)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GOG Galaxy/2.0"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            Log.d("BH_GOG", "console: ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView, req: WebResourceRequest,
                        ): Boolean {
                            val uri = req.url
                            if (uri.toString().startsWith(GogLibrary.REDIRECT_PREFIX)) {
                                handleRedirect(uri)
                                return true
                            }
                            return false
                        }
                        override fun onPageFinished(v: WebView, url: String) {
                            Log.d("BH_GOG", "pageFinished: $url")
                        }
                        override fun onReceivedError(
                            v: WebView, req: WebResourceRequest, err: WebResourceError,
                        ) {
                            Log.e("BH_GOG", "error ${err.errorCode} ${err.description} url=${req.url}")
                        }
                        override fun onReceivedHttpError(
                            v: WebView, req: WebResourceRequest, resp: WebResourceResponse,
                        ) {
                            Log.e("BH_GOG", "httpError ${resp.statusCode} url=${req.url}")
                        }
                    }
                    loadUrl(GogLibrary.AUTH_URL)
                }
            },
        )
        if (working) {
            Box(Modifier.fillMaxSize().background(Color(0xCC111111)), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = GogOrange)
                    Spacer(Modifier.height(12.dp))
                    Text("Logging in to GOG…", color = Color(0xFFCCCCCC))
                }
            }
        }
    }
}

// ── Library ─────────────────────────────────────────────────────────────────

@Composable
private fun GogLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val prefs = remember { context.getSharedPreferences(GogPrefs, 0) }
    val scope = rememberCoroutineScope()

    var allGames by remember { mutableStateOf<List<GogGame>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf(prefs.getString("view_mode", "list") ?: "list") }
    var status by remember { mutableStateOf("Loading GOG library…") }
    var syncing by remember { mutableStateOf(false) }

    val installStates = remember { mutableStateMapOf<String, InstallState>() }
    var confirmGame by remember { mutableStateOf<GogGame?>(null) }
    var exePicker by remember { mutableStateOf<Pair<List<String>, Consumer<String>>?>(null) }

    fun stateFor(g: GogGame): InstallState = installStates.getOrPut(g.gameId) {
        InstallState(prefs.getString("gog_exe_${g.gameId}", null) != null)
    }

    // Result launcher for the (still-Java) detail screen — refresh installed flags on return.
    val detailLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        for (g in allGames) stateFor(g).installed = prefs.getString("gog_exe_${g.gameId}", null) != null
    }

    fun openDetail(g: GogGame) {
        val i = Intent(context, GogGameDetailActivity::class.java)
            .putExtra("game_id", g.gameId)
            .putExtra("title", g.title)
            .putExtra("image_url", g.imageUrl)
            .putExtra("description", g.description)
            .putExtra("developer", g.developer)
            .putExtra("category", g.category)
            .putExtra("generation", g.generation)
        detailLauncher.launch(i)
    }

    fun runSync() {
        if (syncing) return
        syncing = true
        scope.launch {
            try {
                val games = withContext(Dispatchers.IO) {
                    GogLibrary.sync(context) { msg -> scope.launch { status = msg } }
                }
                allGames = games
                status = "${games.size} ${if (games.size == 1) "game" else "games"} — tap a card to install"
            } catch (e: GogLibrary.SyncException) {
                status = e.message ?: "Sync failed"
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            } finally {
                syncing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { GogLibrary.loadCache(context) }
        if (cached != null && cached.isNotEmpty()) {
            allGames = cached
            status = "${cached.size} ${if (cached.size == 1) "game" else "games"} — cached  •  tap ↺ to refresh"
        }
        runSync()
    }

    fun startInstall(g: GogGame) {
        val st = stateFor(g)
        st.downloading = true
        st.pct = 0
        st.status = "Starting…"
        st.cancel = GogDownloadManager.startDownload(activity, g, object : GogDownloadManager.Callback {
            override fun onProgress(msg: String, p: Int) {
                scope.launch(Dispatchers.Main) { st.status = msg; st.pct = p }
            }
            override fun onComplete(exePath: String) {
                scope.launch(Dispatchers.Main) {
                    st.downloading = false; st.installed = true; st.pct = 100
                    st.status = "Installed"; st.cancel = null
                }
            }
            override fun onError(msg: String) {
                scope.launch(Dispatchers.Main) {
                    st.downloading = false; st.status = "Error: $msg"; st.cancel = null
                }
            }
            override fun onCancelled() {
                scope.launch(Dispatchers.Main) {
                    st.downloading = false; st.pct = 0; st.status = ""; st.cancel = null
                }
            }
            override fun onSelectExe(candidates: MutableList<String>, onSelected: Consumer<String>) {
                scope.launch(Dispatchers.Main) { exePicker = candidates to onSelected }
            }
        })
    }

    fun onAction(g: GogGame) {
        val st = stateFor(g)
        when {
            st.downloading -> { st.cancel?.run() }
            st.installed -> {
                val exe = prefs.getString("gog_exe_${g.gameId}", null)
                if (exe != null) GogLaunchHelper.addToLauncher(activity, g.title, exe, g.imageUrl)
            }
            else -> confirmGame = g
        }
    }

    val filtered = remember(allGames, query) {
        if (query.isBlank()) allGames
        else allGames.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().background(GogBg)) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(GogCard).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                "GOG Library", color = GogOrange, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            IconButton(onClick = {
                viewMode = when (viewMode) {
                    "list" -> "grid"; "grid" -> "poster"; else -> "list"
                }
                prefs.edit().putString("view_mode", viewMode).apply()
            }) {
                Icon(
                    if (viewMode == "list") Icons.Filled.GridView else Icons.Filled.ViewList,
                    "Toggle view", tint = Color.White,
                )
            }
            IconButton(onClick = { runSync() }, enabled = !syncing) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = Color.White)
            }
        }

        // Search
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search games…", color = Color(0xFF666666)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF222233),
                unfocusedContainerColor = Color(0xFF222233),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Status
        Text(
            status, fontSize = 13.sp,
            color = when {
                status.startsWith("Error") || status.startsWith("Session expired") ||
                    status.startsWith("Failed") || status.startsWith("Not logged in") -> Color(0xFFFF6B6B)
                status.contains("game") && (status.contains("tap") || status.contains("cached")) -> Color(0xFF81C784)
                else -> Color(0xFFCCCCCC)
            },
            modifier = Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(12.dp, 6.dp),
        )

        // Games
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    if (query.isBlank()) "Your GOG library is empty" else "No results for “${query.trim()}”",
                    color = Color(0xFF666666), fontSize = 14.sp,
                )
            }
        } else if (viewMode == "list") {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.gameId }) { g ->
                    GameListCard(g, stateFor(g), onAction = { onAction(g) }, onOpenDetail = { openDetail(g) })
                }
            }
        } else {
            val artHeight = if (viewMode == "poster") 176.dp else 105.dp
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                gridItems(filtered, key = { it.gameId }) { g ->
                    GameGridTile(g, stateFor(g), artHeight, onAction = { onAction(g) }, onOpenDetail = { openDetail(g) })
                }
            }
        }
    }

    confirmGame?.let { g ->
        InstallConfirmDialog(
            game = g,
            onConfirm = { confirmGame = null; startInstall(g) },
            onDismiss = { confirmGame = null },
        )
    }

    exePicker?.let { (candidates, consumer) ->
        ExePickerDialog(
            candidates = candidates,
            onPick = { path ->
                exePicker = null
                Thread { consumer.accept(path) }.start()
            },
        )
    }
}

// ── List card ─────────────────────────────────────────────────────────────

@Composable
private fun GameListCard(
    game: GogGame, st: InstallState, onAction: () -> Unit, onOpenDetail: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(GogCard)
            .clickable { onOpenDetail() }.padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = normUrl(game.imageUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(60.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF111122)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (game.generation > 0) {
                        GenBadge(game.generation)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        game.title, color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (st.installed) {
                        Text(" ✓", color = Color(0xFF4CAF50), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                val sub = listOf(game.developer, game.category).filter { it.isNotEmpty() }.joinToString("  ·  ")
                if (sub.isNotEmpty()) {
                    Text(sub, color = Color(0xFF888888), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (st.downloading) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = st.pct / 100f, color = GogOrange,
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            Text("${st.pct}%  ${st.status}", color = GogOrange, fontSize = 12.sp)
        } else if (st.status.isNotEmpty()) {
            Text(st.status, color = Color(0xFFAAAAAA), fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    st.downloading -> Color(0xFFCC3333)
                    st.installed -> Color(0xFF2E7D32)
                    else -> GogPurple
                }
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    st.downloading -> "Cancel"
                    st.installed -> "Add to Launcher"
                    else -> "Install"
                }, color = Color.White,
            )
        }
    }
}

// ── Grid tile ─────────────────────────────────────────────────────────────

@Composable
private fun GameGridTile(
    game: GogGame, st: InstallState, artHeight: androidx.compose.ui.unit.Dp,
    onAction: () -> Unit, onOpenDetail: () -> Unit,
) {
    Column(
        Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFF111122))
            .clickable { onOpenDetail() },
    ) {
        Box {
            AsyncImage(
                model = normUrl(game.imageUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(artHeight).background(Color(0xFF0D0D1A)),
            )
            if (game.generation > 0) {
                Box(Modifier.padding(4.dp)) { GenBadge(game.generation) }
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color(0xEE000000)).padding(4.dp, 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    game.title, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (st.installed) Text(" ✓", color = Color(0xFF66BB6A), fontSize = 10.sp)
            }
        }
        if (st.downloading) {
            LinearProgressIndicator(
                progress = st.pct / 100f, color = GogOrange,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(0.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    st.downloading -> Color(0xFFCC3333)
                    st.installed -> Color(0xFF2E7D32)
                    else -> Color(0xFF5533CC)
                }
            ),
            modifier = Modifier.fillMaxWidth().height(30.dp),
        ) {
            Text(
                when {
                    st.downloading -> "Cancel"
                    st.installed -> "Launcher"
                    else -> "Install"
                }, color = Color.White, fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun GenBadge(generation: Int) {
    Text(
        "Gen $generation", color = Color.White, fontSize = 8.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (generation == 2) Color(0xFF0277BD) else Color(0xFFE65100))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

// ── Dialogs ─────────────────────────────────────────────────────────────────

@Composable
private fun InstallConfirmDialog(game: GogGame, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var sizeText by remember { mutableStateOf("Fetching…") }
    var freeText by remember { mutableStateOf("") }
    var notEnough by remember { mutableStateOf(false) }

    LaunchedEffect(game.gameId) {
        val free = withContext(Dispatchers.IO) {
            try {
                val base = GogInstallPath.getInstallDir(context, "_check")
                val parent = base.parentFile?.also { it.mkdirs() }
                val sf = StatFs((parent ?: context.cacheDir).absolutePath)
                sf.availableBlocksLong * sf.blockSizeLong
            } catch (e: Exception) { -1L }
        }
        freeText = GogDownloadManager.formatBytes(free)
        val size = withContext(Dispatchers.IO) { GogDownloadManager.fetchGameSize(context, game) }
        sizeText = GogDownloadManager.formatBytes(size)
        notEnough = size in 1..Long.MAX_VALUE && free in 1..Long.MAX_VALUE && size > free
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install ${game.title}?") },
        text = {
            Column {
                Text(
                    "Game size:  $sizeText" + if (notEnough) "  ⚠ Not enough space" else "",
                    color = if (notEnough) Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text("Available storage:  $freeText", color = if (notEnough) Color(0xFFFF5252) else Color(0xFF4CAF50))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Install") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExePickerDialog(candidates: List<String>, onPick: (String) -> Unit) {
    Dialog(onDismissRequest = { /* not cancelable, mirrors original */ }) {
        Column(
            Modifier.clip(RoundedCornerShape(8.dp)).background(GogCard).padding(16.dp),
        ) {
            Text("Select game executable", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
                items(candidates) { path ->
                    val f = File(path)
                    val label = f.parentFile?.let { "${it.name}/${f.name}" } ?: f.name
                    Text(
                        label, color = Color(0xFFCCCCCC), fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(path) }.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}
