package com.stampshot.app.ui.gallery

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stampshot.app.MainViewModel
import com.stampshot.app.gallery.GalleryPhoto
import com.stampshot.app.gallery.GalleryStore
import com.stampshot.app.share.PrivateShare
import kotlinx.coroutines.launch

@Composable
fun GalleryScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GalleryStore(context) }

    var photos by remember { mutableStateOf<List<GalleryPhoto>?>(null) }
    var sessionFilter by remember { mutableStateOf<String?>(null) }
    var openPhoto by remember { mutableStateOf<GalleryPhoto?>(null) }
    var confirmDelete by remember { mutableStateOf<GalleryPhoto?>(null) }
    var sharePhoto by remember { mutableStateOf<GalleryPhoto?>(null) }

    fun refresh() {
        scope.launch {
            photos = store.loadPhotos()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun openItem(photo: GalleryPhoto) {
        if (!photo.isVideo) {
            openPhoto = photo
            return
        }
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(photo.uri, "video/mp4")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }.onFailure {
            Toast.makeText(context, "No video player found", Toast.LENGTH_SHORT).show()
        }
    }

    val current = openPhoto
    if (current != null) {
        PhotoViewer(
            photo = current,
            store = store,
            onBack = { openPhoto = null },
            onShare = { sharePhoto = current },
            onDelete = { confirmDelete = current },
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to camera")
                }
                Text(
                    "StampShot photos",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            val sessions = photos?.map { it.session }?.distinct() ?: emptyList()
            if (sessions.size > 1) {
                LazyRow(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = sessionFilter == null,
                            onClick = { sessionFilter = null },
                            label = { Text("All") },
                        )
                    }
                    items(sessions) { session ->
                        FilterChip(
                            selected = sessionFilter == session,
                            onClick = { sessionFilter = session },
                            label = { Text(session) },
                        )
                    }
                }
            }

            val list = photos
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No photos yet — take your first stamped shot",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    val filtered = list.filter { sessionFilter == null || it.session == sessionFilter }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp),
                    ) {
                        items(filtered, key = { it.displayName }) { photo ->
                            PhotoCell(photo = photo, store = store) { openItem(photo) }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete photo?") },
            text = { Text(photo.displayName) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        store.delete(photo)
                        confirmDelete = null
                        openPhoto = null
                        refresh()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }

    sharePhoto?.let { photo ->
        ShareSheet(
            viewModel = viewModel,
            onShare = { level ->
                scope.launch {
                    val settings = viewModel.settings.value
                    val result = PrivateShare.share(
                        context, photo.uri, photo.displayName, level, settings.style,
                        isVideo = photo.isVideo,
                        options = com.stampshot.app.stamp.StampOptions(
                            fontScale = settings.fontScale,
                            fontColorArgb = settings.fontColorArgb,
                            bgColorArgb = settings.bgColorArgb,
                            textOpacity = settings.textOpacity,
                            bgOpacity = settings.bgOpacity,
                            position = settings.stampPosition,
                            font = settings.stampFont,
                            transparent = settings.stampTransparent,
                        ),
                        altImperial = !settings.metricUnits,
                    )
                    if (result.fellBackToPixelsOnly) {
                        Toast.makeText(
                            context,
                            "No clean original kept — EXIF stripped, stamp kept as captured",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    result.error?.let {
                        Toast.makeText(context, "Share failed: $it", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { sharePhoto = null },
        )
    }
}

@Composable
private fun PhotoCell(photo: GalleryPhoto, store: GalleryStore, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, photo.uri) {
        value = store.loadThumbnail(photo.uri, 360)
    }
    Box(
        modifier = Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .background(Color(0xFF16191F))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (photo.isVideo) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = "Video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun PhotoViewer(
    photo: GalleryPhoto,
    store: GalleryStore,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, photo.uri) {
        value = store.loadFull(photo.uri)
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .align(Alignment.TopCenter)
                .background(Color(0x66000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                photo.displayName,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .align(Alignment.BottomCenter)
                .background(Color(0x66000000))
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = Color.White)
                Text(" Share", color = Color.White)
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFFF8A80))
                Text(" Delete", color = Color(0xFFFF8A80))
            }
        }
    }
}
