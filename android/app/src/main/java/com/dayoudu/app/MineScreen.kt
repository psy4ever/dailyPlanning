package com.dayoudu.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayoudu.app.data.BackupRepository
import com.dayoudu.app.data.CloudSync
import com.dayoudu.app.data.LocalUserRepository
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun MineScreen(userRepository: LocalUserRepository, backup: BackupRepository,
               padding: PaddingValues, onRestored: () -> Unit) {
    val user by remember { userRepository.observe() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var showAccount by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cloud = remember { CloudSync.get(context) }
    val cloudStatus by cloud.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { runCatching { userRepository.ensure() }.onFailure { message = it.message ?: "本机档案读取失败" } }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { runCatching { backup.export(uri) }
            .onSuccess { message = "已导出 $it 条本机记录" }
            .onFailure { message = it.message ?: "导出失败" } }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri
    }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("我的", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Ink)
        Surface(onClick = { showAccount = true }, shape = RoundedCornerShape(22.dp), color = Mist) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(user?.displayName?.takeIf { it.isNotBlank() } ?: "我的日有度",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                    Text(if (cloudStatus.loggedIn) "已登录云账户 · UID ${cloudStatus.uid.orEmpty()}" else "本机使用 · 点此管理账户",
                        color = MutedInk, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = Pine, style = MaterialTheme.typography.titleLarge)
            }
        }
        Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
        Surface(shape = RoundedCornerShape(22.dp), color = Color.White, border = BorderStroke(1.dp, SoftLine)) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                MineSettingRow("账户与同步", "登录、昵称与同步状态") { showAccount = true }
                MineSettingRow("数据迁移", "导出本机备份，或从日有度备份恢复") { showBackup = true }
            }
        }
        message?.let { Text(it, color = Ink) }
    }
    if (showAccount) AlertDialog(onDismissRequest = { showAccount = false }, title = { Text("账户与同步") },
        text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            CloudPanel(userRepository, user?.displayName)
        } }, confirmButton = { TextButton(onClick = { showAccount = false }) { Text("完成") } })
    if (showBackup) AlertDialog(onDismissRequest = { showBackup = false }, title = { Text("数据迁移") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("“导出”会生成日有度 JSON 备份；“恢复”只能选择此前由日有度导出的备份，并会替换本机记录。普通 TXT 日程请到“今天 → 更多 → 导入日计划”导入。")
            MineSettingRow("导出备份", "保存本机计划、饮食、睡眠和账目") {
                exportPicker.launch("日有度备份-${LocalDate.now()}.json")
            }
            MineSettingRow("从备份恢复", "选择日有度导出的 JSON 文件") {
                importPicker.launch(arrayOf("application/json"))
            }
            Text("备份为明文，请妥善保管。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { showBackup = false }) { Text("完成") } })
    pendingRestore?.let { uri ->
        AlertDialog(onDismissRequest = { pendingRestore = null }, title = { Text("确认恢复备份") },
            text = { Text("恢复会替换本机当前记录。请先保存当前数据，并使用本应用导出的备份文件。") },
            confirmButton = { TextButton(onClick = {
                pendingRestore = null
                scope.launch { runCatching { backup.restore(uri) }
                    .onSuccess { message = "已恢复 $it 条记录"; onRestored() }
                    .onFailure { message = it.message ?: "恢复失败；原数据已保留" } }
            }) { Text("恢复并替换") } },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } })
    }
}

@Composable
private fun MineSettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MutedInk, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = Pine, style = MaterialTheme.typography.titleLarge)
        }
    }
}
