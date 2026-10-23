package com.mollypaw.remote.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mollypaw.remote.data.ChatMessage
import com.mollypaw.remote.data.Repository
import org.json.JSONObject

/**
 * 主界面：聊天 + 停止 + 审批弹窗。
 *
 * 配色取自 BRAND.md（摩尼棕 + 爪印橙）。
 */
@Composable
fun ChatScreen(
    repo: Repository,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApproval: (String, Boolean) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val messages by repo.messages.collectAsState()
    val streaming by repo.streaming.collectAsState()
    val busy by repo.busy.collectAsState()
    val state by repo.state.collectAsState()
    val approval by repo.pendingApproval.collectAsState()
    val error by repo.error.collectAsState()
    val listState = rememberLazyListState()

    Surface(modifier = Modifier.fillMaxSize(), color = MollyColors.Cream) {
        Column(Modifier.fillMaxSize()) {
            // -- 顶部状态条 --
            TopBar(state = state, onStop = onStop, busy = busy)

            // -- 消息列表 --
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages) { msg ->
                    MessageBubble(msg)
                }
                // 流式中的那条。不能用 streaming?.let { } ——
                // let 的 lambda 不是 @Composable，编译器会拒绝。
                val streamText = streaming
                if (streamText != null) {
                    item { MessageBubble(ChatMessage("assistant", streamText), streaming = true) }
                }
            }

            // -- 错误提示 --
            //
            // 这里不能用 error?.let { ... } ——
            // let 的 lambda 不是 @Composable，里面放 Card 会编译报错
            //（"@Composable invocations can only happen from the context of a
            //  @Composable function"）。所以显式判空。
            val errText = error
            if (errText != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF7E5E5)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            errText,
                            color = Color(0xFFA94442),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { repo.clearError() }) { Text("知道了") }
                    }
                }
            }

            // -- 输入区 --
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("说点什么…", color = MollyColors.Tan) },
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (busy) {
                    // 正在跑 → 显示停止按钮
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MollyColors.DeepBrown),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "停止", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("停止")
                    }
                } else {
                    IconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                onSend(text)
                                input = ""
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(MollyColors.Orange, RoundedCornerShape(12.dp)),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }

    // -- 审批弹窗 --
    approval?.let { req ->
        ApprovalDialog(
            request = req,
            onDismiss = { onApproval(req.optString("request_id", ""), false) },
            onConfirm = { ok -> onApproval(req.optString("request_id", ""), ok) },
        )
    }
}

@Composable
private fun TopBar(
    state: Repository.ConnState,
    busy: Boolean,
    onStop: () -> Unit,
) {
    val (label, color) = when (state) {
        Repository.ConnState.CONNECTED -> "已连接" to Color(0xFF3D6F44)
        Repository.ConnState.CONNECTING -> "连接中…" to MollyColors.Tan
        Repository.ConnState.RECONNECTING -> "重连中…" to Color(0xFFA8622A)
        Repository.ConnState.ERROR -> "连接出错" to Color(0xFFA94442)
        Repository.ConnState.DISCONNECTED -> "未连接" to MollyColors.Tan
    }
    Row(
        Modifier.fillMaxWidth().background(MollyColors.DeepBrown).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("MollyPaw", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color(0xFFE8C9A8), fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        if (busy) {
            TextButton(onClick = onStop) {
                Text("停止当前任务", color = Color(0xFFE8C9A8), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, streaming: Boolean = false) {
    val isUser = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MollyColors.Orange else Color.White
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (isUser) "我" else "MollyPaw",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isUser) Color.White else MollyColors.MidBrown,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    msg.content,
                    fontSize = 14.5.sp,
                    color = if (isUser) Color.White else MollyColors.DeepBrown,
                )
                if (streaming) {
                    Spacer(Modifier.height(6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = if (isUser) Color.White else MollyColors.Orange,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalDialog(
    request: JSONObject,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    val name = request.optString("name", "")
    val danger = request.optString("danger_level", "")
    val args = request.opt("args")?.toString() ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("需要你确认", fontWeight = FontWeight.Bold, color = MollyColors.DeepBrown)
        },
        text = {
            Column {
                Text(
                    "电脑上的 AI 想执行：",
                    fontSize = 13.sp, color = MollyColors.MidBrown,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    name,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (danger == "dangerous") Color(0xFFA94442) else MollyColors.DeepBrown,
                )
                if (args.isNotEmpty() && args != "{}") {
                    Spacer(Modifier.height(6.dp))
                    Text(args, fontSize = 12.sp, color = MollyColors.MidBrown, maxLines = 6)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(true) },
                colors = ButtonDefaults.buttonColors(containerColor = MollyColors.Orange),
            ) { Text("同意") }
        },
        dismissButton = {
            OutlinedButton(onClick = { onConfirm(false) }) { Text("拒绝") }
        },
    )
}

/** 品牌色（来自 BRAND.md）。 */
object MollyColors {
    val DeepBrown = Color(0xFF6B4226)
    val MidBrown = Color(0xFF8B5E3C)
    val Tan = Color(0xFFA67B5B)
    val Cream = Color(0xFFFFF8F0)
    val Orange = Color(0xFFD4823A)
    val LightBrown = Color(0xFFF5EDE3)
}

/*
 * StateFlow 收集：直接用 Compose 自带的 collectAsState()。
 *
 * 它本身就是 StateFlow 的 Composable 扩展：
 *     @Composable fun <T> StateFlow<T>.collectAsState(): State<T>
 * 所以只需 import，不需要自己包一层 ——
 * 包一层反而会变成"非 Composable 里调 Composable"，编译直接报
 * "Property delegate must have a 'getValue(Nothing?, KProperty0<*>)' method"。
 */
