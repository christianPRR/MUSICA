package com.musica.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tema oscuro con acento naranja, estilo Poweramp. */
val PowerampScheme = darkColorScheme(
    primary = Color(0xFFFF9800), onPrimary = Color.Black,
    background = Color(0xFF0D0D0D), onBackground = Color.White,
    surface = Color(0xFF0D0D0D), onSurface = Color.White,
    surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainer = Color(0xFF1A1A1A), primaryContainer = Color(0xFF3A2A10), onPrimaryContainer = Color(0xFFFFCC80)
)

private fun keyOf(s: Song, tab: Int): String = when (tab) {
    1 -> if (s.artist.contains("unknown", true) || s.artist.isBlank()) "Desconocido" else s.artist
    2 -> s.album.ifBlank { "Sin álbum" }
    else -> s.genre.ifBlank { "Sin género" }
}

private fun mmss(ms: Long): String { val t = ms / 1000; return "%d:%02d".format(t / 60, t % 60) }

@Composable
fun Art(s: Song, size: Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, s.id) { value = Repo.thumb(ctx, s) }
    Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LibraryScreen(songs: List<Song>, modifier: Modifier, onPlay: (List<Song>, Int) -> Unit) {
    val tabs = listOf("CANCIONES", "ARTISTAS", "ÁLBUMES", "GÉNEROS")
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Pair<Int, String>?>(null) }
    BackHandler(open != null) { open = null }
    Column(modifier) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i; open = null },
                    text = { Text(t, fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val o = open
        if (o != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ open = null }) { Icon(Icons.Default.ArrowBack, "Volver") }
                Text(o.second, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            SongList(songs.filter { keyOf(it, o.first) == o.second }, onPlay)
        } else if (tab == 0) SongList(songs, onPlay)
        else {
            val groups = songs.groupBy { keyOf(it, tab) }.toSortedMap(String.CASE_INSENSITIVE_ORDER).entries.toList()
            LazyColumn {
                itemsIndexed(groups) { _, g ->
                    ListItem(
                        headlineContent = { Text(g.key, maxLines = 1) },
                        supportingContent = { Text("${g.value.size} canciones") },
                        leadingContent = { Art(g.value.first(), 52.dp) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { open = tab to g.key })
                }
            }
        }
    }
}

@Composable
fun SongList(list: List<Song>, onPlay: (List<Song>, Int) -> Unit) {
    LazyColumn {
        itemsIndexed(list) { i, s ->
            ListItem(
                headlineContent = { Text(s.title, maxLines = 1) },
                supportingContent = { Text("${keyOf(s, 1)} · ${keyOf(s, 2)}", maxLines = 1) },
                leadingContent = { Art(s, 52.dp) },
                trailingContent = { Text(mmss(s.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onPlay(list, i) })
        }
    }
}
