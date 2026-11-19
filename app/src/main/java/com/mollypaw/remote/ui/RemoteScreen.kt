package com.mollypaw.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mollypaw.remote.data.Repository
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class RemotePage { Chat, Files, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(repo: Repository, onUnpair: () -> Unit) {
    val state by repo.state.collectAsState()
    val pcOnline by repo.pcOnline.collectAsState()
    val role by repo.role.collectAsState()
    val error by repo.error.collectAsState()
    val notice by repo.notice.collectAsState()
    val approval by repo.pendingApproval.collectAsState()
    val conversations by repo.conversations.collectAsState()
    val currentId by repo.currentConversationId.collectAsState()
    val observer = role == "observer"
    var page by remember { mutableStateOf(RemotePage.Chat) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val currentTitle = conversations.firstOrNull { it.id == currentId }?.title ?: "MollyPaw"
    val title = when (page) {
        RemotePage.Chat -> currentTitle
        RemotePage.Files -> "文件"
        RemotePage.Settings -> "设置"
    }

    LaunchedEffect(Unit) { repo.refreshAll() }
    LaunchedEffect(error) {
        val msg = error
        if (msg != null) {
            snackbar.showSnackbar(msg)
            repo.clearError()
        }
    }
    LaunchedEffect(notice) {
        val msg = notice
        if (msg != null) {
            snackbar.showSnackbar(msg)
            repo.clearNotice()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(320.dp)) {
                SessionDrawer(
                    conversations = conversations,
                    currentId = currentId,
                    isObserver = observer,
                    state = state,
                    stateText = stateLabel(state),
                    pcOnline = pcOnline,
                    onOpen = { id ->
                        scope.launch {
                            repo.switchConversation(id)
                            drawerState.close()
                            page = RemotePage.Chat
                        }
                    },
                    onNew = {
                        scope.launch {
                            repo.newConversation()
                            drawerState.close()
                            page = RemotePage.Chat
                        }
                    },
                    onDelete = { id -> scope.launch { repo.deleteConversation(id) } },
                    onFiles = {
                        scope.launch {
                            drawerState.close()
                            page = RemotePage.Files
                        }
                    },
                    onSettings = {
                        scope.launch {
                            drawerState.close()
                            page = RemotePage.Settings
                        }
                    },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = MollyColors.Cream,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                RemoteTopBar(
                    page = page,
                    title = title,
                    stateText = stateLabel(state),
                    pcOnline = pcOnline,
                    onMenu = { scope.launch { drawerState.open() } },
                    onBack = { page = RemotePage.Chat },
                    onRefresh = {
                        scope.launch {
                            if (page == RemotePage.Settings) {
                                repo.loadConfig()
                                repo.loadDevices()
                            }
                            if (page == RemotePage.Files) repo.refreshFiles()
                        }
                    },
                )
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                when (page) {
                    RemotePage.Chat -> CurrentTab(repo = repo, isObserver = observer)
                    RemotePage.Files -> FilesTab(repo = repo, isObserver = observer)
                    RemotePage.Settings -> SettingsTab(repo = repo, onUnpair = onUnpair)
                }
            }
        }
    }

    if (approval != null && !observer) {
        ApprovalDialog(
            request = approval!!,
            onResolve = { ok ->
                scope.launch { repo.replyApproval(approval!!.optString("request_id"), ok) }
            },
        )
    }
}

@Composable
private fun RemoteTopBar(
    page: RemotePage,
    title: String,
    stateText: String,
    pcOnline: Boolean,
    onMenu: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MollyColors.DeepBrown)
            .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = if (page == RemotePage.Chat) onMenu else onBack) {
            Icon(
                imageVector = if (page == RemotePage.Chat) Icons.Filled.Menu
                else Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = if (page == RemotePage.Chat) "打开会话列表" else "返回",
                tint = MollyColors.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "MollyPaw 远程",
                color = MollyColors.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                title + " · " + stateText + if (pcOnline) " · 电脑在线" else "",
                color = MollyColors.Cream,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        if (page != RemotePage.Chat) {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MollyColors.Cream)
            }
        }
    }
}

@Composable
private fun ApprovalDialog(request: JSONObject, onResolve: (Boolean) -> Unit) {
    val name = request.optString("name", "工具")
    val danger = request.optString("danger_level", "")
    val args = request.opt("args")?.toString() ?: "{}"
    AlertDialog(
        onDismissRequest = { },
        title = { Text("需要你确认", color = MollyColors.DeepBrown, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("电脑端 AI 请求执行：", color = MollyColors.MidBrown, fontSize = 13.sp)
                Text(
                    name,
                    color = if (danger == "dangerous") MollyColors.Error else MollyColors.DeepBrown,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Text(args, color = MollyColors.MidBrown, fontSize = 12.sp, maxLines = 8)
            }
        },
        confirmButton = {
            Button(
                onClick = { onResolve(true) },
                colors = ButtonDefaults.buttonColors(containerColor = MollyColors.Orange),
            ) { Text("同意") }
        },
        dismissButton = {
            OutlinedButton(onClick = { onResolve(false) }) { Text("拒绝") }
        },
    )
}
