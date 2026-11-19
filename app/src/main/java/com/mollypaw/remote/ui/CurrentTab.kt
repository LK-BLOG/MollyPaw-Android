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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mollypaw.remote.data.ChatMessage
import com.mollypaw.remote.data.ConnState
import com.mollypaw.remote.data.Repository
import com.mollypaw.remote.data.ToolCallInfo
import kotlinx.coroutines.launch

@Composable
fun CurrentTab(repo: Repository, isObserver: Boolean) {
    val messages by repo.messages.collectAsState()
    val streaming by repo.streaming.collectAsState()
    val toolCalls by repo.toolCalls.collectAsState()
    val busy by repo.busy.collectAsState()
    val stopping by repo.stopping.collectAsState()
    val state by repo.state.collectAsState()
    val currentId by repo.currentConversationId.collectAsState()
    val approval by repo.pendingApproval.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    val itemCount = messages.size + (if (streaming != null) 1 else 0) + toolCalls.size
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (currentId.isNullOrBlank()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("还没有选择会话", color = MollyColors.LightBrown)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages) { msg ->
                    if (msg.role == "tool") {
                        ToolCard(
                            name = msg.toolName ?: "工具",
                            args = msg.toolArgs ?: "",
                            result = msg.toolResult,
                            danger = msg.toolDanger,
                            running = msg.toolRunning,
                            rejected = false,
                        )
                    } else {
                        MessageBubble(msg)
                    }
                }
                if (streaming != null) {
                    item { StreamingBubble(streaming ?: "") }
                }
                items(toolCalls, key = { it.id }) { call ->
                    ToolCard(
                        name = call.name,
                        args = call.args,
                        result = call.result,
                        danger = call.danger,
                        running = call.running,
                        rejected = call.rejected,
                    )
                }
            }
        }

        if (approval != null && isObserver) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MollyColors.WarmGray),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    "有工具审批请求，只读设备不能处理，请到电脑或控制端操作。",
                    color = MollyColors.Warning,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MollyColors.White),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            stopping -> "正在停止"
                            busy -> "AI 正在执行"
                            state == ConnState.CONNECTED -> "待命"
                            else -> stateLabel(state)
                        },
                        color = if (busy) MollyColors.Warning else MollyColors.MidBrown,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { scope.launch { repo.stop() } },
                        enabled = busy && !stopping,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "停止", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (stopping) "停止中" else "停止任务", fontSize = 12.sp)
                    }
                }
                if (!isObserver) {
                    Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("给电脑端 Agent 发消息", color = MollyColors.LightBrown) },
                            maxLines = 4,
                            shape = RoundedCornerShape(12.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                val text = input.trim()
                                if (text.isNotEmpty()) {
                                    input = ""
                                    scope.launch { repo.send(text) }
                                }
                            },
                            enabled = input.isNotBlank() && !busy && state == ConnState.CONNECTED,
                            modifier = Modifier
                                .size(50.dp)
                                .background(MollyColors.Orange, RoundedCornerShape(12.dp)),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = Color.White,
                            )
                        }
                    }
                } else {
                    Text(
                        "只读设备：只能查看、切换会话和停止任务",
                        color = MollyColors.Tan,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(13.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MollyColors.Orange else MollyColors.White
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (isUser) "你" else "MollyPaw",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isUser) Color.White else MollyColors.MidBrown,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    msg.content,
                    fontSize = 14.sp,
                    color = if (isUser) Color.White else MollyColors.DeepBrown,
                )
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Card(
        Modifier.fillMaxWidth(0.88f),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = MollyColors.White),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("MollyPaw", fontSize = 10.sp, color = MollyColors.MidBrown, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(text.ifBlank { "..." }, fontSize = 14.sp, color = MollyColors.DeepBrown)
            Spacer(Modifier.height(6.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 2.dp,
                color = MollyColors.Orange,
            )
        }
    }
}

@Composable
private fun ToolCard(
    name: String,
    args: String,
    result: String?,
    danger: String?,
    running: Boolean,
    rejected: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.cardColors(containerColor = MollyColors.WarmGray),
    ) {
        Column(Modifier.padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("工具", color = MollyColors.Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp))
                Text(name, color = MollyColors.DeepBrown, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        rejected -> "已拒绝"
                        running -> "执行中"
                        result != null -> "已完成"
                        else -> ""
                    },
                    color = when {
                        rejected -> MollyColors.Error
                        running -> MollyColors.Warning
                        else -> MollyColors.LightBrown
                    },
                    fontSize = 11.sp,
                )
            }
            if (expanded) {
                if (args.isNotBlank() && args != "{}") {
                    Spacer(Modifier.height(6.dp))
                    Text("参数", color = MollyColors.MidBrown, fontSize = 10.sp)
                    Text(args, color = MollyColors.MidBrown, fontSize = 11.sp)
                }
                if (!result.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("结果", color = MollyColors.MidBrown, fontSize = 10.sp)
                    Text(result, color = MollyColors.Ink, fontSize = 11.sp, maxLines = 14)
                }
            }
        }
    }
}
