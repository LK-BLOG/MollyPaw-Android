package com.mollypaw.remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mollypaw.remote.data.ConnState
import com.mollypaw.remote.data.ConversationInfo

@Composable
fun SessionDrawer(
    conversations: List<ConversationInfo>,
    currentId: String?,
    isObserver: Boolean,
    state: ConnState,
    stateText: String,
    pcOnline: Boolean,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ConversationInfo?>(null) }

    Column(Modifier.fillMaxHeight().width(320.dp).padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 14.dp)) {
            Text("MollyPaw", color = MollyColors.DeepBrown, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Your AI, right at your paw.",
                color = MollyColors.LightBrown,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(state)
                Spacer(Modifier.width(7.dp))
                Text(
                    stateText + if (pcOnline) " · 电脑在线" else " · 电脑离线",
                    color = MollyColors.MidBrown,
                    fontSize = 11.sp,
                )
            }
        }

        if (!isObserver) {
            Button(
                onClick = onNew,
                colors = ButtonDefaults.buttonColors(containerColor = MollyColors.Orange),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.height(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建会话")
            }
        }

        Text(
            "会话",
            color = MollyColors.LightBrown,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp),
        )

        LazyColumn(Modifier.weight(1f)) {
            items(conversations, key = { it.id }) { conv ->
                NavigationDrawerItem(
                    label = {
                        Text(
                            conv.title,
                            color = MollyColors.DeepBrown,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    },
                    selected = conv.id == currentId,
                    onClick = { onOpen(conv.id) },
                    badge = if (!isObserver) {
                        { TextButton(onClick = { pendingDelete = conv }) { Text("删除", fontSize = 11.sp, color = MollyColors.Error) } }
                    } else null,
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MollyColors.Tan.copy(alpha = 0.35f),
                        unselectedContainerColor = MollyColors.White,
                    ),
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        HorizontalDivider(color = MollyColors.WarmGray)
        NavigationDrawerItem(
            label = { Text("文件", color = MollyColors.DeepBrown) },
            icon = { Icon(Icons.Filled.Folder, contentDescription = null, tint = MollyColors.MidBrown) },
            selected = false,
            onClick = onFiles,
            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = MollyColors.White),
            shape = RoundedCornerShape(9.dp),
        )
        NavigationDrawerItem(
            label = { Text("设置", color = MollyColors.DeepBrown) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = MollyColors.MidBrown) },
            selected = false,
            onClick = onSettings,
            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = MollyColors.White),
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }

    pendingDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话", color = MollyColors.DeepBrown) },
            text = { Text("删除「" + conv.title + "」？这个操作不能撤销。", color = MollyColors.MidBrown) },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(conv.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MollyColors.Error),
                ) { Text("删除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}
