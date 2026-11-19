package com.mollypaw.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.mollypaw.remote.data.DeviceInfo
import com.mollypaw.remote.data.Repository
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun SettingsTab(repo: Repository, onUnpair: () -> Unit) {
    val state by repo.state.collectAsState()
    val pcOnline by repo.pcOnline.collectAsState()
    val role by repo.role.collectAsState()
    val deviceName by repo.deviceName.collectAsState()
    val devId by repo.devId.collectAsState()
    val relayUrl by repo.relayUrl.collectAsState()
    val config by repo.config.collectAsState()
    val devices by repo.devices.collectAsState()
    val scope = rememberCoroutineScope()
    val observer = role == "observer"
    var model by remember(config) { mutableStateOf(config?.optString("model") ?: "") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        item {
            SectionCard("连接") {
                InfoRow("状态", stateLabel(state))
                InfoRow("电脑", if (pcOnline) "在线" else "离线")
                InfoRow("设备名", deviceName.ifBlank { "未命名" })
                InfoRow("角色", if (observer) "只读" else "控制端")
                InfoRow("中继", relayUrl.ifBlank { "官方中继" })
            }
        }

        item {
            SectionCard("电脑设置") {
                if (observer) {
                    Text("只读设备不能修改电脑设置。", color = MollyColors.LightBrown, fontSize = 12.sp)
                } else {
                    val cfg = config
                    if (cfg == null) {
                        Text("还没有读取到配置。", color = MollyColors.LightBrown, fontSize = 12.sp)
                    } else {
                        InfoRow("供应商", cfg.optString("provider_id", "-"))
                        InfoRow("思考等级", cfg.optString("thinking_level", "-"))
                        InfoRow("审批模式", cfg.optString("approval_mode", "-"))
                        InfoRow("温度", cfg.optDouble("temperature", 0.0).toString())
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text("模型 ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                        )
                        Spacer(Modifier.height(7.dp))
                        Button(
                            onClick = {
                                val patch = JSONObject().apply { put("model", model) }
                                scope.launch { repo.saveConfig(patch) }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MollyColors.Orange),
                        ) { Text("保存模型") }
                    }
                }
            }
        }

        item {
            SectionCard("设备") {
                if (devices.isEmpty()) {
                    Text("没有已配对设备。", color = MollyColors.LightBrown, fontSize = 12.sp)
                } else {
                    devices.forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    d.name.ifBlank { "未命名设备" } + if (d.id == devId) "（本机）" else "",
                                    color = MollyColors.DeepBrown,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    if (d.role == "observer") "只读" else "控制端",
                                    color = MollyColors.LightBrown,
                                    fontSize = 10.sp,
                                )
                            }
                            if (!observer && d.id != devId) {
                                TextButton(onClick = {
                                    scope.launch { repo.revokeDevice(d.id) }
                                }) { Text("吊销", color = MollyColors.Error, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onUnpair,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) { Text("解除配对", color = MollyColors.Error) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MollyColors.White),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = MollyColors.DeepBrown, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MollyColors.LightBrown, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Text(value, color = MollyColors.DeepBrown, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}
