package br.com.iptvcaseiro

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import br.com.iptvcaseiro.data.AppDatabase
import br.com.iptvcaseiro.data.Channel
import br.com.iptvcaseiro.util.BackupCrypto
import br.com.iptvcaseiro.util.M3uParser
import br.com.iptvcaseiro.util.VersionUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val APK_NAME = "iptv-caseiro.apk"
private const val PREFS = "iptv_caseiro"
private const val UPDATE_CHECKED_AT = "update_checked_at"
private const val BUNDLED_CATALOG_ASSET = "bundled-catalog.iptvbak"
private const val BUNDLED_CATALOG_UNLOCKED = "bundled_catalog_unlocked_2"
private const val DAY_MS = 24L * 60L * 60L * 1000L

private enum class Screen { HOME, MANAGE, EDIT, IMPORT, BACKUP, SERIES, PLAYER }

private data class XtreamAccess(val root: String, val username: String, val password: String)

private sealed interface UpdateState {
    data object Hidden : UpdateState
    data object Checking : UpdateState
    data class Current(val found: String) : UpdateState
    data class Available(val found: String, val apkUrl: String) : UpdateState
    data class MissingApk(val found: String) : UpdateState
    data class Error(val reason: String) : UpdateState
}

private sealed interface PasswordAction {
    data object Export : PasswordAction
    data class Restore(val uri: Uri) : PasswordAction
}

class MainActivity : ComponentActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val channelsState = mutableStateOf<List<Channel>>(emptyList())
    private val screenState = mutableStateOf(Screen.HOME)
    private val editingState = mutableStateOf<Channel?>(null)
    private val playingState = mutableStateOf<Channel?>(null)
    private val noticeState = mutableStateOf<String?>(null)
    private val updateState = mutableStateOf<UpdateState>(UpdateState.Hidden)
    private val passwordActionState = mutableStateOf<PasswordAction?>(null)
    private val playlistPreviewState = mutableStateOf<List<Channel>>(emptyList())
    private val playlistLoadingState = mutableStateOf(false)
    private val bundledCatalogLockedState = mutableStateOf(true)
    private val bundledCatalogLoadingState = mutableStateOf(false)
    private val bundledCatalogErrorState = mutableStateOf<String?>(null)
    private val seriesState = mutableStateOf<Channel?>(null)
    private val seriesEpisodesState = mutableStateOf<List<Channel>>(emptyList())
    private val seriesLoadingState = mutableStateOf(false)
    private val seriesErrorState = mutableStateOf<String?>(null)
    private val playerReturnScreenState = mutableStateOf(Screen.HOME)
    private var checkingUpdates = false
    private var activePlayer: ExoPlayer? = null
    private var pendingApk: File? = null
    private var downloadId = -1L
    private var waitingInstallPermission = false
    private var exportPassword: CharArray? = null

    private lateinit var localVideoPicker: ActivityResultLauncher<Array<String>>
    private lateinit var playlistPicker: ActivityResultLauncher<Array<String>>
    private lateinit var pcDatabasePicker: ActivityResultLauncher<Array<String>>
    private lateinit var backupCreator: ActivityResultLauncher<String>
    private lateinit var backupPicker: ActivityResultLauncher<Array<String>>

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor: Cursor? ->
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) installDownloadedApk()
                    else showNotice("Não foi possível baixar a atualização.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit().remove("server_url").apply()
        bundledCatalogLockedState.value = !prefs.getBoolean(BUNDLED_CATALOG_UNLOCKED, false)
        registerLaunchers()
        registerDownloadReceiver()
        loadChannels()
        setContent {
            IptvTheme {
                IptvApp()
            }
        }
        if (System.currentTimeMillis() - prefs.getLong(UPDATE_CHECKED_AT, 0L) >= DAY_MS) {
            prefs.edit().putLong(UPDATE_CHECKED_AT, System.currentTimeMillis()).apply()
            checkForUpdates(manual = false)
        }
    }

    private fun registerLaunchers() {
        localVideoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistReadPermission(uri)
                val draft = editingState.value ?: Channel()
                draft.sourceType = "LOCAL"
                draft.source = uri.toString()
                if (draft.name.isBlank()) draft.name = uri.lastPathSegment?.substringAfterLast('/') ?: "Vídeo local"
                editingState.value = copyChannel(draft)
                screenState.value = Screen.EDIT
            }
        }
        playlistPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistReadPermission(uri)
                playlistLoadingState.value = true
                executor.execute {
                    try {
                        contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "Não foi possível abrir o arquivo." }
                            showPlaylistPreview(M3uParser.parse(input))
                        }
                    } catch (error: Exception) {
                        runOnUiThread { playlistLoadingState.value = false }
                        showNotice(error.message ?: "Falha ao importar a playlist.")
                    }
                }
            }
        }
        pcDatabasePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPcDatabase(uri)
        }
        backupCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val password = exportPassword
            exportPassword = null
            if (uri != null && password != null) exportBackup(uri, password) else password?.fill('\u0000')
        }
        backupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) passwordActionState.value = PasswordAction.Restore(uri)
        }
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Alguns provedores concedem acesso sem oferecer permissão persistente.
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun IptvApp() {
        val screen by screenState
        val channels by channelsState
        val playing by playingState
        BackHandler(enabled = screen != Screen.HOME) {
            screenState.value = when (screen) {
                Screen.MANAGE -> Screen.HOME
                Screen.PLAYER -> playerReturnScreenState.value
                Screen.SERIES -> Screen.HOME
                else -> Screen.MANAGE
            }
        }
        Scaffold(
            topBar = {
                if (screen != Screen.PLAYER) {
                    TopAppBar(
                        title = { Text("IPTV Caseiro") },
                        navigationIcon = {
                            if (screen != Screen.HOME) TextButton(onClick = { screenState.value = Screen.HOME }) { Text("Voltar") }
                        },
                        actions = {
                            TextButton(enabled = !checkingUpdates, onClick = { checkForUpdates(true) }) { Text("Atualizar") }
                            if (screen == Screen.HOME) TextButton(onClick = { screenState.value = Screen.MANAGE }) { Text("Gerenciar") }
                        },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    Screen.HOME -> HomeScreen(channels)
                    Screen.MANAGE -> ManageScreen(channels)
                    Screen.EDIT -> EditorScreen(editingState.value)
                    Screen.IMPORT -> ImportScreen()
                    Screen.BACKUP -> BackupScreen()
                    Screen.SERIES -> seriesState.value?.let { SeriesScreen(it) }
                    Screen.PLAYER -> if (playing != null) {
                        val episodes = seriesEpisodesState.value
                        val playable = if (episodes.any { it.source == playing!!.source }) episodes else channels.filter { it.active && it.sourceType !in setOf("EXTERNAL", "SERIES") }
                        PlayerScreen(playing!!, playable)
                    }
                }
            }
        }
        noticeState.value?.let { notice ->
            AlertDialog(
                onDismissRequest = { noticeState.value = null },
                title = { Text("IPTV Caseiro") },
                text = { Text(notice) },
                confirmButton = { TextButton(onClick = { noticeState.value = null }) { Text("OK") } },
            )
        }
        UpdateDialog(updateState.value)
        passwordActionState.value?.let { PasswordDialog(it) }
        if (bundledCatalogLockedState.value) BundledCatalogDialog()
    }

    @Composable
    private fun HomeScreen(channels: List<Channel>) {
        var query by rememberSaveable { mutableStateOf("") }
        var favoritesOnly by rememberSaveable { mutableStateOf(false) }
        var category by rememberSaveable { mutableStateOf("Todos") }
        val activeChannels = remember(channels) { channels.filter { it.active } }
        val categoryCounts = remember(activeChannels) { activeChannels.groupingBy { it.category }.eachCount() }
        val categories = remember(categoryCounts) {
            listOf("Todos") + categoryCounts.keys.filterNot { it.equals("Todos", true) }.sorted()
        }
        val filtered = activeChannels.filter {
            (!favoritesOnly || it.favorite) &&
                (category == "Todos" || it.category == category) &&
                (query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true))
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Pesquisar canais") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val availableWidth = maxWidth
                val sidebarWidth = if (availableWidth >= 600.dp) 190.dp else 128.dp
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(
                        Modifier.width(sidebarWidth).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Pastas", fontWeight = FontWeight.Bold)
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = { favoritesOnly = !favoritesOnly },
                            label = { Text("★ Favoritos") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(categories, key = { it }) { folder ->
                                val count = if (folder == "Todos") activeChannels.size else categoryCounts[folder] ?: 0
                                FilterChip(
                                    selected = category == folder,
                                    onClick = { category = folder },
                                    label = { Text(if (folder == "Todos") "Todos ($count)" else "📁 $folder ($count)", maxLines = 2) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Text(if (activeChannels.isEmpty()) "Seu catálogo está vazio" else "Nenhum canal encontrado nesta pasta.")
                        }
                    } else {
                        val minimum = if (availableWidth >= 900.dp) 240.dp else 160.dp
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minimum),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(filtered, key = { it.id }) { channel -> ChannelCard(channel) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyCatalog(onManage: () -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Seu catálogo está vazio", style = MaterialTheme.typography.headlineSmall)
                Text("Cadastre um canal, escolha um vídeo ou importe uma playlist M3U.")
                Button(onClick = onManage) { Text("Adicionar conteúdo") }
            }
        }
    }

    @Composable
    private fun ChannelCard(channel: Channel) {
        Card(
            onClick = {
                when (channel.sourceType) {
                    "EXTERNAL" -> openExternalLink(channel.source)
                    "SERIES" -> openSeries(channel)
                    else -> {
                        playerReturnScreenState.value = Screen.HOME
                        playingState.value = channel
                        screenState.value = Screen.PLAYER
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(130.dp).focusable(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2)
                    TextButton(onClick = { toggleFavorite(channel) }) { Text(if (channel.favorite) "★" else "☆") }
                }
                Text(channel.category, color = MaterialTheme.colorScheme.secondary)
                Text(
                    when (channel.sourceType) {
                        "LOCAL" -> "Vídeo do aparelho"
                        "EXTERNAL" -> "Link externo · ${redactSource(channel.source)}"
                        "SERIES" -> "Série · toque para ver os episódios"
                        else -> redactSource(channel.source)
                    },
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun ManageScreen(channels: List<Channel>) {
        var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editingState.value = Channel(); screenState.value = Screen.EDIT }) { Text("Novo") }
                Button(onClick = { openExternalLinkEditor() }) { Text("Link externo") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { screenState.value = Screen.IMPORT }) { Text("Importar M3U") }
                OutlinedButton(onClick = { screenState.value = Screen.BACKUP }) { Text("Backup") }
            }
            if (selected.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { setActive(selected, true); selected = emptySet() }) { Text("Ativar") }
                    OutlinedButton(onClick = { setActive(selected, false); selected = emptySet() }) { Text("Desativar") }
                    OutlinedButton(onClick = { deleteChannels(selected); selected = emptySet() }) { Text("Excluir") }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(channels, key = { it.id }) { channel ->
                    Card(Modifier.fillMaxWidth().clickable { editingState.value = copyChannel(channel); screenState.value = Screen.EDIT }.focusable()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = channel.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + channel.id else selected - channel.id })
                            Column(Modifier.weight(1f)) {
                                Text(channel.name, fontWeight = FontWeight.Bold)
                                Text("${channel.category} · ${if (channel.active) "Ativo" else "Inativo"}")
                                Text(redactSource(channel.source), maxLines = 1)
                            }
                            Text("Editar")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EditorScreen(initial: Channel?) {
        val model = initial ?: Channel()
        var name by remember(model.id, model.source) { mutableStateOf(model.name) }
        var description by remember(model.id, model.source) { mutableStateOf(model.description) }
        var category by remember(model.id, model.source) { mutableStateOf(model.category) }
        var logo by remember(model.id, model.source) { mutableStateOf(model.logoUrl) }
        var source by remember(model.id, model.source) { mutableStateOf(model.source) }
        var sourceType by remember(model.id, model.source) { mutableStateOf(model.sourceType) }
        var reveal by rememberSaveable(model.id) { mutableStateOf(false) }
        var active by remember(model.id, model.source) { mutableStateOf(model.active) }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when {
                    model.id != 0L -> "Editar conteúdo"
                    model.sourceType == "EXTERNAL" -> "Novo link externo"
                    else -> "Novo conteúdo"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(category, { category = it }, label = { Text("Categoria") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(logo, { logo = it }, label = { Text("URL do logotipo (opcional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                source, { source = it }, label = { Text(if (sourceType == "EXTERNAL") "Endereço do link externo" else "URL do vídeo/stream") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (!reveal && isSensitive(source)) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            if (sourceType == "EXTERNAL") {
                Text("O endereço será salvo sem download nem extração e abrirá no navegador.")
            }
            if (isSensitive(source)) TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Ocultar credenciais" else "Mostrar para editar") }
            Text("Tipo de conteúdo", fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = sourceType == "STREAM",
                    onClick = { sourceType = "STREAM" },
                    label = { Text("Stream ou vídeo online — reproduzir no aplicativo") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FilterChip(
                    selected = sourceType == "EXTERNAL",
                    onClick = { sourceType = "EXTERNAL" },
                    label = { Text("Link externo — abrir no navegador") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sourceType == "LOCAL") {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("Vídeo local — arquivo do aparelho") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(active, { active = it })
                Text("Ativo")
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { localVideoPicker.launch(arrayOf("video/*")) }) { Text("Escolher vídeo do aparelho") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (name.isBlank() || source.isBlank()) { showNotice("Informe o nome e a origem do conteúdo."); return@Button }
                    if (!source.startsWith("http://") && !source.startsWith("https://") && !source.startsWith("content://")) {
                        showNotice("Use uma URL HTTP/HTTPS ou escolha um vídeo do aparelho.")
                        return@Button
                    }
                    model.name = name.trim(); model.category = category.trim().ifBlank { "Sem categoria" }
                    model.description = description.trim(); model.logoUrl = logo.trim(); model.source = source.trim(); model.active = active
                    model.sourceType = if (model.source.startsWith("content://")) "LOCAL" else sourceType
                    saveChannel(model)
                }) { Text("Salvar") }
                OutlinedButton(onClick = { screenState.value = Screen.MANAGE }) { Text("Cancelar") }
            }
            Text("Versão instalada: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun ImportScreen() {
        var url by rememberSaveable { mutableStateOf("") }
        var reveal by rememberSaveable { mutableStateOf(false) }
        val preview by playlistPreviewState
        val loading by playlistLoadingState
        var selected by remember(preview) { mutableStateOf(preview.indices.toSet()) }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Importar playlist M3U", style = MaterialTheme.typography.headlineSmall)
            Text("Aceita playlists M3U e acessos Xtream. Canais, filmes, séries e credenciais ficam somente neste aparelho.")
            OutlinedTextField(
                url, { url = it }, label = { Text("URL da playlist") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (!reveal && isSensitive(url)) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            if (isSensitive(url)) TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Ocultar credenciais" else "Mostrar para editar") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { previewFromUrl(url) }, enabled = !loading && (url.trim().startsWith("http://") || url.trim().startsWith("https://"))) { Text("Buscar canais") }
                OutlinedButton(enabled = !loading, onClick = { playlistPicker.launch(arrayOf("audio/x-mpegurl", "application/x-mpegURL", "text/plain", "*/*")) }) { Text("Escolher arquivo") }
            }
            OutlinedButton(
                enabled = !loading && (url.trim().startsWith("http://") || url.trim().startsWith("https://")),
                onClick = { openExternalLinkEditor(url) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Usar como link externo (sem baixar)") }
            Text("Importar M3U extrai os canais. Link externo apenas guarda o endereço e o abre no navegador.")
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Buscando canais e vídeos…")
            }
            if (preview.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selected.size == preview.size, onCheckedChange = { all -> selected = if (all) preview.indices.toSet() else emptySet() })
                    Text("Selecionar todos", modifier = Modifier.weight(1f))
                    Text("${preview.size} encontrados")
                }
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = { importChannels(preview.filterIndexed { index, _ -> index in selected }) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Importar ${selected.size} selecionados") }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(preview, key = { index, channel -> "${channel.source}-$index" }) { index, channel ->
                        Card(Modifier.fillMaxWidth().clickable { selected = if (index in selected) selected - index else selected + index }) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = index in selected, onCheckedChange = { checked -> selected = if (checked) selected + index else selected - index })
                                Column(Modifier.weight(1f)) {
                                    Text(channel.name, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(channel.category, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BackupScreen() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Backup criptografado", style = MaterialTheme.typography.headlineSmall)
            Text("O arquivo usa AES-256-GCM e só pode ser restaurado com a senha. A senha não é salva.")
            Button(onClick = { passwordActionState.value = PasswordAction.Export }) { Text("Criar backup") }
            OutlinedButton(onClick = { backupPicker.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("Restaurar backup") }
            Text("Transferir catálogo do computador", style = MaterialTheme.typography.titleMedium)
            Text("Escolha uma cópia do arquivo database/banco.db do IPTV Caseiro. Streams e links externos serão importados sem consultar seus servidores.")
            OutlinedButton(onClick = { pcDatabasePicker.launch(arrayOf("application/vnd.sqlite3", "application/octet-stream", "*/*")) }) {
                Text("Importar banco do computador")
            }
            Text("Vídeos locais continuam protegidos pelo Android. Em outro aparelho, talvez seja necessário selecioná-los novamente.")
        }
    }

    @Composable
    private fun PasswordDialog(action: PasswordAction) {
        var password by remember { mutableStateOf("") }
        var replace by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { passwordActionState.value = null },
            title = { Text(if (action is PasswordAction.Export) "Senha do backup" else "Restaurar backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(password, { password = it }, label = { Text("Senha (mínimo 8 caracteres)") }, visualTransformation = PasswordVisualTransformation())
                    if (action is PasswordAction.Restore) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(replace, { replace = it }); Text(if (replace) "Substituir catálogo atual" else "Mesclar com o catálogo atual") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { passwordActionState.value = null }) { Text("Cancelar") } },
            confirmButton = {
                TextButton(enabled = password.length >= 8, onClick = {
                    passwordActionState.value = null
                    val chars = password.toCharArray()
                    when (action) {
                        PasswordAction.Export -> { exportPassword = chars; backupCreator.launch("iptv-caseiro-backup.iptvbak") }
                        is PasswordAction.Restore -> restoreBackup(action.uri, chars, replace)
                    }
                    password = ""
                }) { Text("Continuar") }
            },
        )
    }

    @Composable
    private fun BundledCatalogDialog() {
        var password by rememberSaveable { mutableStateOf("") }
        val loading by bundledCatalogLoadingState
        val error by bundledCatalogErrorState
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Desbloquear catálogo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Esta versão contém um catálogo privado criptografado. Informe a senha para liberar os canais neste aparelho.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; bundledCatalogErrorState.value = null },
                        label = { Text("Senha") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        enabled = !loading,
                    )
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotBlank() && !loading,
                    onClick = {
                        val chars = password.toCharArray()
                        password = ""
                        unlockBundledCatalog(chars)
                    },
                ) { Text(if (loading) "Desbloqueando…" else "Desbloquear") }
            },
        )
    }

    @Composable
    private fun SeriesScreen(series: Channel) {
        val episodes by seriesEpisodesState
        val loading by seriesLoadingState
        val error by seriesErrorState
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(series.name, style = MaterialTheme.typography.headlineSmall)
            Text(series.category, color = MaterialTheme.colorScheme.secondary)
            when {
                loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Buscando temporadas e episódios…")
                }
                error != null -> {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { openSeries(series) }) { Text("Tentar novamente") }
                }
                episodes.isEmpty() -> Text("Nenhum episódio foi encontrado para esta série.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(episodes, key = { it.source }) { episode ->
                        Card(
                            onClick = {
                                playerReturnScreenState.value = Screen.SERIES
                                playingState.value = episode
                                screenState.value = Screen.PLAYER
                            },
                            modifier = Modifier.fillMaxWidth().focusable(),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(episode.name, fontWeight = FontWeight.Bold)
                                Text(episode.category, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PlayerScreen(channel: Channel, playable: List<Channel>) {
        val context = LocalContext.current
        val player = remember(channel.id, channel.source) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(channel.source))
                prepare()
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }
        }
        DisposableEffect(player) {
            activePlayer = player
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                if (activePlayer === player) activePlayer = null
                player.release()
                WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopCenter).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { screenState.value = playerReturnScreenState.value }) { Text("Voltar") }
                val index = playable.indexOfFirst { it.source == channel.source }
                if (playable.size > 1 && index >= 0) {
                    OutlinedButton(onClick = { playingState.value = playable[(index - 1 + playable.size) % playable.size] }) { Text("Anterior") }
                    OutlinedButton(onClick = { playingState.value = playable[(index + 1) % playable.size] }) { Text("Próximo") }
                }
            }
        }
    }

    @Composable
    private fun UpdateDialog(state: UpdateState) {
        when (state) {
            UpdateState.Hidden -> Unit
            UpdateState.Checking -> AlertDialog(
                onDismissRequest = {}, title = { Text("Verificando atualização") },
                text = { Text("Consultando a versão mais recente…") }, confirmButton = {},
            )
            is UpdateState.Current -> UpdateResultDialog("Aplicativo atualizado", "Versão instalada: ${BuildConfig.VERSION_NAME}\nVersão encontrada: ${state.found}")
            is UpdateState.Available -> AlertDialog(
                onDismissRequest = { updateState.value = UpdateState.Hidden },
                title = { Text("Atualização disponível") },
                text = { Text("Versão instalada: ${BuildConfig.VERSION_NAME}\nVersão encontrada: ${state.found}") },
                dismissButton = { TextButton(onClick = { updateState.value = UpdateState.Hidden }) { Text("Agora não") } },
                confirmButton = { TextButton(onClick = { updateState.value = UpdateState.Hidden; downloadUpdate(state.apkUrl) }) { Text("Baixar e instalar") } },
            )
            is UpdateState.MissingApk -> AlertDialog(
                onDismissRequest = { updateState.value = UpdateState.Hidden }, title = { Text("Release sem APK") },
                text = { Text("Versão instalada: ${BuildConfig.VERSION_NAME}\nVersão encontrada: ${state.found}\n\nA Release não contém $APK_NAME.") },
                dismissButton = { TextButton(onClick = { updateState.value = UpdateState.Hidden; openDownloadPage() }) { Text("Abrir página") } },
                confirmButton = { TextButton(onClick = { updateState.value = UpdateState.Hidden }) { Text("Fechar") } },
            )
            is UpdateState.Error -> AlertDialog(
                onDismissRequest = { updateState.value = UpdateState.Hidden }, title = { Text("Falha ao verificar atualização") },
                text = { Text("${state.reason}\n\nVersão instalada: ${BuildConfig.VERSION_NAME}") },
                dismissButton = { TextButton(onClick = { updateState.value = UpdateState.Hidden; openDownloadPage() }) { Text("Abrir página de download") } },
                confirmButton = { TextButton(onClick = { updateState.value = UpdateState.Hidden; checkForUpdates(true) }) { Text("Tentar novamente") } },
            )
        }
    }

    @Composable
    private fun UpdateResultDialog(title: String, text: String) {
        AlertDialog(onDismissRequest = { updateState.value = UpdateState.Hidden }, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = { updateState.value = UpdateState.Hidden }) { Text("OK") } })
    }

    private fun loadChannels(after: (() -> Unit)? = null) {
        executor.execute {
            val rows = AppDatabase.get(this).channelDao().all()
            runOnUiThread { channelsState.value = rows; after?.invoke() }
        }
    }

    private fun openExternalLinkEditor(value: String = "") {
        editingState.value = Channel().apply {
            name = if (value.isBlank()) "" else "Link externo"
            category = "Links externos"
            sourceType = "EXTERNAL"
            source = value.trim()
        }
        screenState.value = Screen.EDIT
    }

    private fun saveChannel(channel: Channel) {
        executor.execute {
            try {
                val dao = AppDatabase.get(this).channelDao()
                if (channel.id == 0L) {
                    if (dao.insert(channel) == -1L) { showNotice("Este endereço já está cadastrado."); return@execute }
                } else dao.update(channel)
                loadChannels { screenState.value = Screen.MANAGE }
            } catch (_: android.database.sqlite.SQLiteConstraintException) {
                showNotice("Este endereço já está cadastrado em outro item.")
            }
        }
    }

    private fun toggleFavorite(channel: Channel) {
        val changed = copyChannel(channel).apply { favorite = !channel.favorite }
        executor.execute { AppDatabase.get(this).channelDao().update(changed); loadChannels() }
    }

    private fun setActive(ids: Set<Long>, active: Boolean) {
        executor.execute { AppDatabase.get(this).channelDao().setActive(ids.toList(), active); loadChannels() }
    }

    private fun deleteChannels(ids: Set<Long>) {
        executor.execute { AppDatabase.get(this).channelDao().deleteIds(ids.toList()); loadChannels() }
    }

    private fun previewFromUrl(value: String) {
        val normalized = value.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) { showNotice("Informe uma URL HTTP ou HTTPS válida."); return }
        playlistLoadingState.value = true
        playlistPreviewState.value = emptyList()
        executor.execute {
            try {
                val access = parseXtreamAccess(normalized)
                val channels = if (access != null) fetchXtreamCatalog(access) else {
                    val bytes = fetchHttpBytes(normalized, "application/x-mpegURL, audio/x-mpegurl, text/plain, */*", M3uParser.MAX_BYTES)
                    M3uParser.parse(ByteArrayInputStream(bytes))
                }
                showPlaylistPreview(channels)
            } catch (error: Exception) {
                runOnUiThread { playlistLoadingState.value = false }
                showNotice(error.message ?: "Falha ao importar a playlist.")
            }
        }
    }

    private fun parseXtreamAccess(value: String): XtreamAccess? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.rawAuthority.isNullOrBlank()) return null
        val parameters = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.isEmpty()) null else URLDecoder.decode(pieces[0], StandardCharsets.UTF_8.name()) to URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
        }.toMap()
        val username = parameters["username"].orEmpty()
        val password = parameters["password"].orEmpty()
        if (username.isBlank() || password.isBlank()) return null
        val directory = uri.rawPath.orEmpty().substringBeforeLast('/', "")
        return XtreamAccess("${uri.scheme}://${uri.rawAuthority}$directory", username, password)
    }

    private fun xtreamApiUrl(access: XtreamAccess, action: String, extraName: String? = null, extraValue: String? = null): String {
        val encode = { text: String -> URLEncoder.encode(text, StandardCharsets.UTF_8.name()).replace("+", "%20") }
        return buildString {
            append(access.root).append("/player_api.php?username=").append(encode(access.username))
            append("&password=").append(encode(access.password)).append("&action=").append(encode(action))
            if (extraName != null && extraValue != null) append('&').append(encode(extraName)).append('=').append(encode(extraValue))
        }
    }

    private fun xtreamStreamUrl(access: XtreamAccess, kind: String, id: String, extension: String): String {
        val encode = { text: String -> URLEncoder.encode(text, StandardCharsets.UTF_8.name()).replace("+", "%20") }
        val safeExtension = extension.filter { it.isLetterOrDigit() }.ifBlank { "ts" }
        return "${access.root}/$kind/${encode(access.username)}/${encode(access.password)}/${encode(id)}.$safeExtension"
    }

    private fun fetchXtreamCatalog(access: XtreamAccess): List<Channel> {
        fun categories(action: String): Map<String, String> {
            val array = JSONArray(String(fetchHttpBytes(xtreamApiUrl(access, action), "application/json", 8 * 1024 * 1024), StandardCharsets.UTF_8))
            return buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    put(item.optString("category_id"), item.optString("category_name").ifBlank { "Sem categoria" })
                }
            }
        }
        val result = ArrayList<Channel>()
        val seen = HashSet<String>()
        fun addItems(categoryAction: String, itemAction: String, sourceType: String) {
            val categoryNames = categories(categoryAction)
            val bytes = fetchHttpBytes(xtreamApiUrl(access, itemAction), "application/json", 64 * 1024 * 1024)
            val array = JSONArray(String(bytes, StandardCharsets.UTF_8))
            for (index in 0 until array.length()) {
                if (result.size >= M3uParser.MAX_CHANNELS) throw IllegalStateException("O catálogo ultrapassa o limite de ${M3uParser.MAX_CHANNELS} itens.")
                val item = array.optJSONObject(index) ?: continue
                val idName = if (sourceType == "SERIES") "series_id" else "stream_id"
                val id = item.optString(idName).takeUnless { it.isBlank() || it == "null" } ?: continue
                val extension = item.optString("container_extension", "ts")
                val source = when (sourceType) {
                    "SERIES" -> xtreamApiUrl(access, "get_series_info", "series_id", id)
                    "VOD" -> xtreamStreamUrl(access, "movie", id, extension)
                    else -> xtreamStreamUrl(access, "live", id, extension)
                }
                if (!seen.add(source)) continue
                result += Channel().apply {
                    name = item.optString("name").ifBlank { item.optString("title") }.ifBlank { "Conteúdo sem nome" }
                    description = item.optString("plot").take(500)
                    category = categoryNames[item.optString("category_id")].orEmpty().ifBlank { "Sem categoria" }
                    logoUrl = if (sourceType == "SERIES") item.optString("cover") else item.optString("stream_icon")
                    this.sourceType = if (sourceType == "VOD") "STREAM" else sourceType
                    this.source = source
                    active = true
                }
            }
        }
        addItems("get_live_categories", "get_live_streams", "STREAM")
        addItems("get_vod_categories", "get_vod_streams", "VOD")
        addItems("get_series_categories", "get_series", "SERIES")
        return result
    }

    private fun fetchHttpBytes(address: String, accept: String, maximum: Int): ByteArray {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(address).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "IPTV-Caseiro/1.0")
            connection.setRequestProperty("Accept", accept)
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("O servidor respondeu com o código $code.")
            if (connection.contentLengthLong > maximum) throw IllegalStateException("A resposta do servidor ultrapassa o limite permitido.")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maximum) throw IllegalStateException("A resposta do servidor ultrapassa o limite permitido.")
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        } finally {
            connection?.disconnect()
        }
    }

    private fun openSeries(series: Channel) {
        seriesState.value = series
        seriesEpisodesState.value = emptyList()
        seriesErrorState.value = null
        seriesLoadingState.value = true
        screenState.value = Screen.SERIES
        executor.execute {
            try {
                val access = parseXtreamAccess(series.source) ?: throw IllegalStateException("Os dados de acesso desta série são inválidos.")
                val root = JSONObject(String(fetchHttpBytes(series.source, "application/json", 32 * 1024 * 1024), StandardCharsets.UTF_8))
                val episodesObject = root.optJSONObject("episodes") ?: throw IllegalStateException("O servidor não retornou os episódios desta série.")
                val seasons = mutableListOf<String>()
                val keys = episodesObject.keys()
                while (keys.hasNext()) seasons += keys.next()
                seasons.sortWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
                val episodes = ArrayList<Channel>()
                for (season in seasons) {
                    val array = episodesObject.optJSONArray(season) ?: continue
                    for (index in 0 until array.length()) {
                        if (episodes.size >= M3uParser.MAX_CHANNELS) throw IllegalStateException("A série possui episódios demais.")
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optString("id").takeUnless { it.isBlank() || it == "null" } ?: continue
                        val extension = item.optString("container_extension", "mp4")
                        episodes += Channel().apply {
                            name = item.optString("title").ifBlank { "Episódio ${item.optString("episode_num", (index + 1).toString())}" }
                            category = "Temporada $season"
                            sourceType = "STREAM"
                            source = xtreamStreamUrl(access, "series", id, extension)
                            active = true
                        }
                    }
                }
                runOnUiThread {
                    seriesEpisodesState.value = episodes
                    seriesLoadingState.value = false
                }
            } catch (error: Exception) {
                runOnUiThread {
                    seriesLoadingState.value = false
                    seriesErrorState.value = error.message ?: "Não foi possível carregar os episódios."
                }
            }
        }
    }

    private fun showPlaylistPreview(channels: List<Channel>) = runOnUiThread {
        playlistPreviewState.value = channels
        playlistLoadingState.value = false
    }

    private fun importChannels(channels: List<Channel>) {
        playlistLoadingState.value = true
        executor.execute {
            val dao = AppDatabase.get(this).channelDao()
            var added = 0
            channels.chunked(1_000).forEach { batch -> added += dao.insertAll(batch).count { it != -1L } }
            val duplicates = channels.size - added
            loadChannels {
                playlistLoadingState.value = false
                playlistPreviewState.value = emptyList()
                showNotice("Importação concluída: $added adicionados e $duplicates repetidos ignorados.")
                screenState.value = Screen.MANAGE
            }
        }
    }

    private fun importPcDatabase(uri: Uri) {
        executor.execute {
            val temporary = File(cacheDir, "pc-catalog-import.db")
            try {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Não foi possível abrir o banco do computador." }
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > 100L * 1024L * 1024L) throw IllegalStateException("O banco ultrapassa o limite de 100 MB.")
                            output.write(buffer, 0, read)
                        }
                    }
                }

                val imported = mutableListOf<Channel>()
                var localVideos = 0
                SQLiteDatabase.openDatabase(temporary.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                    database.rawQuery(
                        "SELECT nome, descricao, categoria, logo, tipo, fonte, ativo, favorito FROM canais ORDER BY id",
                        null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            if (imported.size >= M3uParser.MAX_CHANNELS) throw IllegalStateException("O banco contém canais demais.")
                            val source = cursor.getString(5)?.trim().orEmpty()
                            if (source.isBlank()) continue
                            val sourceType = when (cursor.getString(4)?.lowercase()) {
                                "stream" -> "STREAM"
                                "externo" -> "EXTERNAL"
                                "arquivo" -> "LOCAL"
                                else -> continue
                            }
                            if (sourceType == "LOCAL") localVideos++
                            imported += Channel().apply {
                                name = cursor.getString(0)?.trim().orEmpty().ifBlank { "Conteúdo sem nome" }.take(120)
                                description = cursor.getString(1)?.trim().orEmpty().take(500)
                                category = cursor.getString(2)?.trim().orEmpty().ifBlank { "Sem categoria" }.take(80)
                                logoUrl = cursor.getString(3)?.trim().orEmpty()
                                this.sourceType = sourceType
                                this.source = source
                                active = cursor.getInt(6) != 0 && sourceType != "LOCAL"
                                favorite = cursor.getInt(7) != 0
                            }
                        }
                    }
                }
                if (imported.isEmpty()) throw IllegalStateException("O banco do computador não contém canais compatíveis.")
                val added = AppDatabase.get(this).channelDao().insertAll(imported).count { it != -1L }
                val duplicates = imported.size - added
                loadChannels {
                    val localWarning = if (localVideos > 0) " $localVideos vídeos locais ficaram inativos e precisam ser escolhidos novamente." else ""
                    showNotice("Transferência concluída: $added adicionados e $duplicates repetidos ignorados.$localWarning")
                    screenState.value = Screen.MANAGE
                }
            } catch (error: Exception) {
                showNotice(error.message ?: "Não foi possível importar o banco do computador.")
            } finally {
                temporary.delete()
            }
        }
    }

    private fun unlockBundledCatalog(password: CharArray) {
        if (bundledCatalogLoadingState.value) {
            password.fill('\u0000')
            return
        }
        bundledCatalogLoadingState.value = true
        bundledCatalogErrorState.value = null
        executor.execute {
            try {
                val encrypted = assets.open(BUNDLED_CATALOG_ASSET).use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > 8 * 1024 * 1024) throw IllegalStateException("O catálogo incluído ultrapassa o limite permitido.")
                    bytes
                }
                val plain = BackupCrypto.decrypt(encrypted, password)
                val root = JSONObject(String(plain, StandardCharsets.UTF_8))
                if (root.optInt("format") != 1) throw IllegalStateException("O catálogo incluído possui formato incompatível.")
                val array = root.getJSONArray("channels")
                if (array.length() > M3uParser.MAX_CHANNELS) throw IllegalStateException("O catálogo incluído contém canais demais.")
                val channels = ArrayList<Channel>(array.length())
                for (index in 0 until array.length()) channels += channelFromJson(array.getJSONObject(index))
                val added = AppDatabase.get(this).channelDao().insertAll(channels).count { it != -1L }
                val duplicates = channels.size - added
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(BUNDLED_CATALOG_UNLOCKED, true).apply()
                loadChannels {
                    bundledCatalogLoadingState.value = false
                    bundledCatalogLockedState.value = false
                    showNotice("Catálogo desbloqueado: $added canais adicionados e $duplicates repetidos ignorados.")
                }
            } catch (_: javax.crypto.AEADBadTagException) {
                runOnUiThread {
                    bundledCatalogLoadingState.value = false
                    bundledCatalogErrorState.value = "Senha incorreta. Tente novamente."
                }
            } catch (_: Exception) {
                runOnUiThread {
                    bundledCatalogLoadingState.value = false
                    bundledCatalogErrorState.value = "Não foi possível desbloquear o catálogo incluído."
                }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun exportBackup(uri: Uri, password: CharArray) {
        executor.execute {
            try {
                val array = JSONArray()
                AppDatabase.get(this).channelDao().all().forEach { channel -> array.put(channelToJson(channel)) }
                val root = JSONObject().put("format", 1).put("channels", array).toString().toByteArray(StandardCharsets.UTF_8)
                val encrypted = BackupCrypto.encrypt(root, password)
                contentResolver.openOutputStream(uri, "w").use { output -> requireNotNull(output); output.write(encrypted) }
                showNotice("Backup criptografado criado com sucesso.")
            } catch (error: Exception) {
                showNotice(error.message ?: "Não foi possível criar o backup.")
            } finally { password.fill('\u0000') }
        }
    }

    private fun restoreBackup(uri: Uri, password: CharArray, replace: Boolean) {
        executor.execute {
            try {
                val encrypted = contentResolver.openInputStream(uri).use { input -> requireNotNull(input); input.readBytes() }
                if (encrypted.size > 4 * 1024 * 1024) throw IllegalStateException("O backup ultrapassa o limite permitido.")
                val plain = BackupCrypto.decrypt(encrypted, password)
                val array = JSONObject(String(plain, StandardCharsets.UTF_8)).getJSONArray("channels")
                if (array.length() > M3uParser.MAX_CHANNELS) throw IllegalStateException("O backup contém canais demais.")
                val dao = AppDatabase.get(this).channelDao()
                if (replace) dao.deleteAll()
                var added = 0
                for (index in 0 until array.length()) if (dao.insert(channelFromJson(array.getJSONObject(index))) != -1L) added++
                loadChannels { showNotice("Backup restaurado: $added itens adicionados."); screenState.value = Screen.MANAGE }
            } catch (_: javax.crypto.AEADBadTagException) {
                showNotice("Senha incorreta ou arquivo de backup adulterado.")
            } catch (error: Exception) {
                showNotice(error.message ?: "Não foi possível restaurar o backup.")
            } finally { password.fill('\u0000') }
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        if (checkingUpdates) return
        checkingUpdates = true
        if (manual) updateState.value = UpdateState.Checking
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPOSITORY}/releases/latest").openConnection() as HttpURLConnection
                connection.connectTimeout = 12_000; connection.readTimeout = 12_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "IPTV-Caseiro-Android")
                val code = connection.responseCode
                if (code != 200) throw IllegalStateException("O GitHub respondeu com o código $code.")
                val body = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
                val release = JSONObject(body)
                val found = release.getString("tag_name").replace(Regex("^[vV]"), "")
                var apk = ""
                val assets = release.getJSONArray("assets")
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    if (asset.optString("name").equals(APK_NAME, true)) apk = asset.getString("browser_download_url")
                }
                runOnUiThread {
                    if (VersionUtils.isNewer(found, BuildConfig.VERSION_NAME)) {
                        updateState.value = if (apk.isBlank()) UpdateState.MissingApk(found) else UpdateState.Available(found, apk)
                    } else if (manual) updateState.value = UpdateState.Current(found)
                }
            } catch (error: Exception) {
                if (manual) runOnUiThread { updateState.value = UpdateState.Error(error.message ?: "Erro de conexão ou resposta inválida.") }
            } finally { connection?.disconnect(); checkingUpdates = false }
        }
    }

    private fun downloadUpdate(apkUrl: String) {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
        if (file.exists() && !file.delete()) { showNotice("Não foi possível substituir o instalador anterior."); return }
        pendingApk = file
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Atualização do IPTV Caseiro").setDescription("Baixando nova versão")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
        downloadId = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        showNotice("Download iniciado. A instalação será aberta quando terminar.")
    }

    private fun installDownloadedApk() {
        val file = pendingApk
        if (file == null || !file.isFile) { showNotice("O arquivo da atualização não foi encontrado."); return }
        if (!packageManager.canRequestPackageInstalls()) {
            waitingInstallPermission = true
            AlertDialog.Builder(this).setTitle("Permitir instalação")
                .setMessage("Autorize o IPTV Caseiro a instalar aplicativos desconhecidos e volte para continuar.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Abrir configuração") { _, _ -> startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .show()
            return
        }
        waitingInstallPermission = false
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openDownloadPage() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.GITHUB_REPOSITORY}/releases/latest"))) }
        catch (_: Exception) { showNotice("Nenhum aplicativo conseguiu abrir a página de download.") }
    }

    private fun openExternalLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { showNotice("Nenhum aplicativo conseguiu abrir este link.") }
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun channelToJson(channel: Channel) = JSONObject()
        .put("name", channel.name).put("description", channel.description).put("category", channel.category)
        .put("logoUrl", channel.logoUrl).put("sourceType", channel.sourceType).put("source", channel.source)
        .put("active", channel.active).put("favorite", channel.favorite).put("createdAt", channel.createdAt)

    private fun channelFromJson(json: JSONObject) = Channel().apply {
        name = json.getString("name"); description = json.optString("description"); category = json.optString("category", "Sem categoria")
        logoUrl = json.optString("logoUrl"); sourceType = json.optString("sourceType", "STREAM"); source = json.getString("source")
        active = json.optBoolean("active", true); favorite = json.optBoolean("favorite", false); createdAt = json.optLong("createdAt", System.currentTimeMillis())
        if (sourceType == "LOCAL") active = false
    }

    private fun copyChannel(source: Channel) = Channel().also {
        it.id = source.id; it.name = source.name; it.description = source.description; it.category = source.category
        it.logoUrl = source.logoUrl; it.sourceType = source.sourceType; it.source = source.source
        it.active = source.active; it.favorite = source.favorite; it.createdAt = source.createdAt
    }

    private fun showNotice(text: String) = runOnUiThread { noticeState.value = text }

    override fun onResume() {
        super.onResume()
        if (waitingInstallPermission && packageManager.canRequestPackageInstalls()) installDownloadedApk()
    }

    override fun onStop() {
        activePlayer?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        try { unregisterReceiver(downloadReceiver) } catch (_: IllegalArgumentException) {}
        activePlayer?.release(); activePlayer = null
        executor.shutdownNow()
        super.onDestroy()
    }
}

internal fun isSensitive(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (uri.isOpaque) return false
    val privateNames = setOf("token", "key", "password", "pass", "username", "user", "auth")
    val hasPrivateQuery = uri.rawQuery.orEmpty().split('&').any { parameter ->
        parameter.substringBefore('=').lowercase() in privateNames
    }
    return !uri.rawUserInfo.isNullOrBlank() || hasPrivateQuery || hasXtreamPathCredentials(uri)
}

internal fun redactSource(value: String): String {
    if (value.startsWith("content://")) return "Arquivo local protegido"
    val uri = runCatching { URI(value) }.getOrNull() ?: return "Origem protegida"
    if (uri.isOpaque) return value
    if (!isSensitive(value)) return value
    val port = if (uri.port > 0) ":${uri.port}" else ""
    if (hasXtreamPathCredentials(uri)) {
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        val typeIndex = segments.indexOfFirst { it.lowercase() in setOf("live", "movie", "series") }
        val prefix = segments.take(typeIndex + 1).joinToString("/")
        val item = segments.lastOrNull().orEmpty()
        return "${uri.scheme ?: "https"}://${uri.host ?: "origem"}$port/$prefix/•••/•••/$item"
    }
    return "${uri.scheme ?: "https"}://${uri.host ?: "origem"}$port${uri.rawPath ?: ""}?…"
}

private fun hasXtreamPathCredentials(uri: URI): Boolean {
    val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
    val typeIndex = segments.indexOfFirst { it.lowercase() in setOf("live", "movie", "series") }
    return typeIndex >= 0 && segments.size >= typeIndex + 4
}

@Composable
private fun IptvTheme(content: @Composable () -> Unit) {
    val colors: ColorScheme = darkColorScheme(
        primary = Color(0xFF66D9EF), secondary = Color(0xFF9AE6B4), background = Color(0xFF0B1220), surface = Color(0xFF172033),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
