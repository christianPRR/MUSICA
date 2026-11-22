package com.musica.app

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Song(val id: Long, val title: String, val artist: String, val durationMs: Long, val item: MediaItem)

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var future: ListenableFuture<MediaController>? = null

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({ controller = f.get() }, { it.run() })
    }

    override fun onStop() {
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MusicaApp() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MusicaApp() {
        val ctx = LocalContext.current
        val dark = isSystemInDarkTheme()
        val scheme = if (Build.VERSION.SDK_INT >= 31)
            (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
        else (if (dark) darkColorScheme() else lightColorScheme())
        MaterialTheme(colorScheme = scheme) {
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                       else Manifest.permission.READ_EXTERNAL_STORAGE
            var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
            var granted by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                granted = it; if (it) songs = loadSongs()
            }
            LaunchedEffect(Unit) { launcher.launch(perm) }

            var playing by remember { mutableStateOf(false) }
            var currentId by remember { mutableStateOf<String?>(null) }
            var showPlayer by remember { mutableStateOf(false) }
            DisposableEffect(controller) {
                val c = controller
                playing = c?.isPlaying ?: false
                currentId = c?.currentMediaItem?.mediaId
                val l = object : Player.Listener {
                    override fun onIsPlayingChanged(p: Boolean) { playing = p }
                    override fun onMediaItemTransition(m: MediaItem?, r: Int) { currentId = m?.mediaId }
                }
                c?.addListener(l)
                onDispose { c?.removeListener(l) }
            }
            val current = songs.firstOrNull { it.id.toString() == currentId }

            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = { LargeTopAppBar(title = { Text("MUSICA") }) },
                    bottomBar = {
                        Surface(tonalElevation = 3.dp, modifier = Modifier.clickable(enabled = current != null) { showPlayer = true }) {
                            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(current?.title ?: "Elige una canción", Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton({ controller?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null) }
                                FilledIconButton({ controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                                }
                                IconButton({ controller?.seekToNext() }) { Icon(Icons.Default.SkipNext, null) }
                            }
                        }
                    }
                ) { pad ->
                    if (!granted || songs.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                            Text(if (!granted) "Permite el acceso a tu música" else "No se encontraron canciones")
                        }
                    } else LazyColumn(Modifier.padding(pad)) {
                        itemsIndexed(songs) { i, s ->
                            ListItem(
                                headlineContent = { Text(s.title, maxLines = 1) },
                                supportingContent = { Text(s.artist, maxLines = 1) },
                                leadingContent = { Icon(Icons.Default.MusicNote, null) },
                                modifier = Modifier.clickable {
                                    controller?.run {
                                        setMediaItems(songs.map { it.item }, i, 0L)
                                        prepare(); play()
                                    }
                                    showPlayer = true
                                })
                        }
                    }
                }
                if (showPlayer && current != null) {
                    BackHandler { showPlayer = false }
                    PlayerScreen(current, controller, playing) { showPlayer = false }
                }
            }
        }
    }

    @Composable
    fun PlayerScreen(song: Song, c: MediaController?, playing: Boolean, onClose: () -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var cover by remember(song.id) { mutableStateOf<Bitmap?>(null) }
        var lyrics by remember(song.id) { mutableStateOf<Lyrics?>(null) }
        var status by remember(song.id) { mutableStateOf("Buscando letra…") }
        var busy by remember(song.id) { mutableStateOf(false) }
        var pos by remember { mutableLongStateOf(0L) }

        fun setLyrics(t: String?) {
            lyrics = t?.let { Repo.parse(it) }
            status = if (t == null) "No se encontró letra" else ""
        }
        LaunchedEffect(song.id) {
            cover = Repo.loadCover(ctx, song) ?: Repo.downloadCover(ctx, song)
            setLyrics(Repo.loadLyrics(ctx, song, false))
        }
        LaunchedEffect(c) { while (true) { pos = c?.currentPosition ?: 0L; delay(300) } }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth()) {
                    IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar") }
                }
                Box(Modifier.size(240.dp).clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    val b = cover
                    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Default.MusicNote, null, Modifier.size(80.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(song.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                val dur = song.durationMs.coerceAtLeast(1L)
                Slider(value = (pos.toFloat() / dur).coerceIn(0f, 1f),
                    onValueChange = { pos = (it * dur).toLong(); c?.seekTo(pos) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ c?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null) }
                    FilledIconButton({ c?.let { if (it.isPlaying) it.pause() else it.play() } }, Modifier.size(64.dp)) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(32.dp))
                    }
                    IconButton({ c?.seekToNext() }) { Icon(Icons.Default.SkipNext, null) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            Repo.downloadCover(ctx, song)?.let { cover = it }
                            busy = false
                        }
                    }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Portada") }
                    OutlinedButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true; status = "Buscando letra…"
                            setLyrics(Repo.loadLyrics(ctx, song, true))
                            busy = false
                        }
                    }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Letra") }
                }
                Spacer(Modifier.height(8.dp))
                val l = lyrics
                if (l == null || l.lines.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(status) }
                } else {
                    val idx = if (l.synced) l.lines.indexOfLast { it.first <= pos } else -1
                    val state = rememberLazyListState()
                    LaunchedEffect(idx) { if (idx >= 0) state.animateScrollToItem((idx - 2).coerceAtLeast(0)) }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
                        itemsIndexed(l.lines) { i, line ->
                            val active = i == idx
                            Text(line.second, Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                textAlign = TextAlign.Center,
                                fontSize = if (active) 22.sp else 18.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active || !l.synced) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    private fun loadSongs(): List<Song> {
        val out = mutableListOf<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
        contentResolver.query(uri, cols, "${MediaStore.Audio.Media.IS_MUSIC}!=0", null,
            "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0); val t = c.getString(1) ?: "Sin título"
                val a = c.getString(2) ?: "Desconocido"; val d = c.getLong(3)
                val item = MediaItem.Builder().setMediaId(id.toString())
                    .setUri(ContentUris.withAppendedId(uri, id))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(t).setArtist(a).build()).build()
                out.add(Song(id, t, a, d, item))
            }
        }
        return out
    }
}
