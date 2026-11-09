package com.metrolist.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.metrolist.desktop.playback.PlayerController
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--smoke" in args) {
        exitProcess(runSmoke())
    }
    runUi()
}

private fun runUi() = application {
    initLocale()
    val controller = remember { PlayerController() }
    Window(
        onCloseRequest = {
            controller.close()
            exitApplication()
        },
        title = "Metrolist",
        state = rememberWindowState(width = 440.dp, height = 760.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            PlayerScreen(controller)
        }
    }
}

/** System/runner locales can report an empty country; InnerTube rejects gl="". */
private fun initLocale() {
    val system = Locale.getDefault()
    YouTube.locale =
        YouTubeLocale(
            gl = system.country.ifEmpty { "US" },
            hl = system.language.ifEmpty { "en" },
        )
}

/**
 * Headless health check (`--smoke`) for CI and packaging: resolve one real
 * stream, play3s through libmpv and exit 0 only after a full load → play →
 * EOF cycle. ao is forced to null because runners have no sound card.
 */
private fun runSmoke(): Int =
    runBlocking {
        initLocale()
        val controller =
            PlayerController(playerOptions = mapOf("ao" to "null", "vo" to "null", "length" to "3"))
        try {
            controller.play(
                listOf(
                    SongItem(id = "dQw4w9WgXcQ", title = "smoke", artists = emptyList(), thumbnail = ""),
                ),
                0,
            )
            withTimeout(60_000) { controller.isPlaying.first { it } }
            withTimeout(60_000) { controller.isPlaying.first { !it } }
            println("smoke: load -> play -> EOF OK")
            0
        } catch (t: Throwable) {
            System.err.println("smoke failed: $t")
            1
        } finally {
            controller.close()
        }
    }

@Composable
private fun PlayerScreen(controller: PlayerController) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val queue by controller.queue.collectAsState()
    val currentIndex by controller.currentIndex.collectAsState()
    val isPlaying by controller.isPlaying.collectAsState()

    val items = if (results.isNotEmpty()) results else queue
    val currentId = queue.getOrNull(currentIndex)?.id

    Scaffold(
        bottomBar = {
            PlayerBar(
                track = queue.getOrNull(currentIndex),
                isPlaying = isPlaying,
                onToggle = controller::togglePlayPause,
                onPrev = controller::previous,
                onNext = controller::next,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search YouTube Music") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val q = query.trim()
                        if (q.isEmpty()) return@Button
                        searching = true
                        scope.launch {
                            results =
                                YouTube.search(q, YouTube.SearchFilter.FILTER_SONG)
                                    .onFailure { System.err.println("search failed: $it") }
                                    .getOrNull()
                                    ?.items
                                    ?.filterIsInstance<SongItem>()
                                    ?: emptyList()
                            searching = false
                        }
                    },
                    enabled = !searching,
                ) {
                    Text("Search")
                }
            }

            when {
                searching -> Text("Searching…", Modifier.padding(16.dp))
                items.isEmpty() -> Text("Search to find something to play", Modifier.padding(16.dp))
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(items) { index, song ->
                            val active = song.id == currentId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { controller.play(items, index) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        song.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (active) FontWeight.Bold else null,
                                        color =
                                            if (active) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                    )
                                    Text(
                                        song.artists.joinToString { it.name },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                song.duration?.let {
                                    Text(
                                        formatDuration(it),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun PlayerBar(
    track: SongItem?,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(tonalElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    track?.title ?: "Not playing",
                    maxLines = 1,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    track?.artists?.joinToString { it.name } ?: "",
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onPrev) { Text("Prev") }
            Button(onClick = onToggle) { Text(if (isPlaying) "Pause" else "Play") }
            TextButton(onClick = onNext) { Text("Next") }
        }
    }
}

private fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
