package com.mollypaw.remote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mollypaw.remote.data.Credentials
import com.mollypaw.remote.data.CredentialsStore
import com.mollypaw.remote.data.Repository
import com.mollypaw.remote.service.RelayService
import com.mollypaw.remote.ui.MollyColors
import com.mollypaw.remote.ui.QrScanScreen
import com.mollypaw.remote.ui.RemoteScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val repo by lazy { Repository(applicationContext) }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MollyColors.Cream) {
                    AppRoot(repo)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(repo: Repository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var creds by remember { mutableStateOf<Credentials?>(null) }
    var checked by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val c = CredentialsStore.load(context)
        creds = c
        checked = true
        if (c.paired) RelayService.start(context)
    }

    if (!checked) return

    if (creds?.paired == true) {
        RemoteScreen(
            repo = repo,
            onUnpair = {
                scope.launch {
                    context.stopService(Intent(context, RelayService::class.java))
                    CredentialsStore.clear(context)
                    creds = null
                }
            },
        )
    } else {
        PairScreen(
            busy = pairing,
            error = pairError,
            onPair = { code, name, relayUrl ->
                pairing = true
                pairError = null
                scope.launch {
                    val result = repo.pair(code, name, relayUrl)
                    result.onSuccess {
                        creds = it
                        pairError = null
                    }.onFailure {
                        pairError = it.message ?: "配对失败"
                    }
                    pairing = false
                }
            },
        )
    }
}

@Composable
private fun PairScreen(
    busy: Boolean,
    error: String?,
    onPair: (String, String, String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("我的手机") }
    var relayUrl by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (scanning) {
        QrScanScreen(
            onDecoded = { text ->
                scanning = false
                code = text
                onPair(text.trim(), name.trim(), relayUrl.trim())
            },
            onClose = { scanning = false },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("连上你的电脑", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MollyColors.DeepBrown)
        Spacer(Modifier.height(8.dp))
        Text(
            "在电脑的 MollyPaw 设置里点「添加设备」，会显示二维码和配对码。\n" +
                "扫码或粘贴配对码即可连接。",
            fontSize = 14.sp,
            color = MollyColors.MidBrown,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { scanning = true },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = MollyColors.Orange),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text("扫码配对", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(10.dp))
        Text("电脑上：设置 → 远程互联 → 开启远程互联 → 添加设备。", fontSize = 12.sp, color = MollyColors.Tan)
        Spacer(Modifier.height(20.dp))
        Text("或者手动粘贴配对码：", fontSize = 13.sp, color = MollyColors.MidBrown)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("配对码") },
            placeholder = { Text("MP1.xxxxx.yyyyy", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("设备名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = relayUrl,
            onValueChange = { relayUrl = it },
            label = { Text("中继地址（可选）") },
            placeholder = { Text("留空使用官方中继") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MollyColors.Error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onPair(code.trim(), name.trim(), relayUrl.trim()) },
            enabled = !busy && code.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = MollyColors.Orange),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(if (busy) "连接中..." else "连接", fontSize = 15.sp) }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                runCatching { context.startActivity(intent) }
            }) { Text("电池优化设置", color = MollyColors.MidBrown, fontSize = 13.sp) }
        }
        Text(
            "把本应用加入电池优化白名单，否则后台连接会被系统掐断。",
            fontSize = 12.sp,
            color = MollyColors.Tan,
        )
    }
}
