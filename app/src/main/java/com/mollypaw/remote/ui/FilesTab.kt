package com.mollypaw.remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.sp
import com.mollypaw.remote.data.FsEntry
import com.mollypaw.remote.data.Repository
import kotlinx.coroutines.launch

@Composable
fun FilesTab(repo: Repository, isObserver: Boolean) {
    val roots by repo.fsRoots.collectAsState()
    val entries by repo.fsEntries.collectAsState()
    val path by repo.fsPath.collectAsState()
    val parent by repo.fsParent.collectAsState()
    val state by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }

    if (isObserver) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("只读设备不能浏览电脑文件", color = MollyColors.LightBrown)
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))

        if (path.isBlank()) {
            Text("选择磁盘", color = MollyColors.MidBrown, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(roots, key = { it.path }) { root ->
                    EntryCard(root, isDir = true, onOpen = {
                        scope.launch {
                            loading = true
                            repo.listFiles(root.path)
                            loading = false
                        }
                    }, onDownload = null)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                parent?.let {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                loading = true
                                repo.listFiles(it)
                                loading = false
                            }
                        },
                        shape = RoundedCornerShape(9.dp),
                    ) { Text("上一级", fontSize = 12.sp) }
                    Spacer(Modifier.padding(horizontal = 4.dp))
                }
                Text(path, color = MollyColors.LightBrown, fontSize = 11.sp, maxLines = 1)
            }
            Spacer(Modifier.height(7.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(entries, key = { it.path }) { entry ->
                    EntryCard(
                        entry = entry,
                        isDir = entry.isDir,
                        onOpen = if (entry.isDir) {
                            {
                                scope.launch {
                                    loading = true
                                    repo.listFiles(entry.path)
                                    loading = false
                                }
                            }
                        } else null,
                        onDownload = if (!entry.isDir) {
                            {
                                scope.launch { repo.downloadFile(entry.path, entry.name) }
                            }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: FsEntry,
    isDir: Boolean,
    onOpen: (() -> Unit)?,
    onDownload: (() -> Unit)?,
) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = onOpen != null) { onOpen?.invoke() },
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.cardColors(containerColor = MollyColors.White),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isDir) "目录" else "文件",
                color = if (isDir) MollyColors.Orange else MollyColors.LightBrown,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, color = MollyColors.DeepBrown, fontSize = 13.sp, maxLines = 1)
                if (!isDir) {
                    Text(formatSize(entry.size), color = MollyColors.LightBrown, fontSize = 10.sp)
                }
            }
            onDownload?.let {
                Button(
                    onClick = it,
                    colors = ButtonDefaults.buttonColors(containerColor = MollyColors.Orange),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("下载", fontSize = 11.sp) }
            }
        }
    }
}

private fun formatSize(size: Long): String {
    if (size < 1024) return size.toString() + " B"
    if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0)
    if (size < 1024L * 1024L * 1024L) return String.format("%.1f MB", size / 1024.0 / 1024.0)
    return String.format("%.1f GB", size / 1024.0 / 1024.0 / 1024.0)
}
