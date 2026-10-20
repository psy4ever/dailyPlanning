package com.dayoudu.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayoudu.app.data.BackupRepository
import com.dayoudu.app.data.LocalUserRepository
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun MineScreen(userRepository: LocalUserRepository, backup: BackupRepository,
               padding: PaddingValues, onRestored: () -> Unit) {
    val user by remember { userRepository.observe() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var showNameEditor by remember { mutableStateOf(false) }
    var name by remember(user?.id, showNameEditor) { mutableStateOf(user?.displayName.orEmpty()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
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
        Surface(shape = RoundedCornerShape(24.dp), color = Mist) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本机档案", color = Pine, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(user?.displayName ?: "未设置昵称", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = Ink)
                    Text("数据保存在本机", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showNameEditor = true }) { Text("编辑昵称  ›") }
                }
                Image(painterResource(R.drawable.diary_sprout), contentDescription = null,
                    modifier = Modifier.size(104.dp), contentScale = ContentScale.Fit)
            }
        }
        Text("备份与恢复", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
        Surface(shape = RoundedCornerShape(22.dp), color = Color.White, border = BorderStroke(1.dp, SoftLine)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = { exportPicker.launch("日有度备份-${LocalDate.now()}.json") },
                    shape = RoundedCornerShape(15.dp), color = Cream) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("导出备份", fontWeight = FontWeight.Bold, color = Ink)
                            Text("保存本机记录", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›", color = Pine, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Surface(onClick = { importPicker.launch(arrayOf("application/json", "text/plain")) },
                    shape = RoundedCornerShape(15.dp), color = Cream) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("从备份恢复", fontWeight = FontWeight.Bold, color = Ink)
                            Text("替换当前本机记录", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›", color = Pine, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        Text("备份文件可能包含私人信息，请妥善保存。", color = MutedInk,
            style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, color = Ink) }
    }
    if (showNameEditor) AlertDialog(onDismissRequest = { showNameEditor = false }, title = { Text("编辑昵称") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("昵称（可留空）") }, singleLine = true)
            nameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { userRepository.rename(name) }
                .onSuccess { showNameEditor = false; nameError = null }
                .onFailure { nameError = it.message ?: "保存失败" }
        } }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { showNameEditor = false }) { Text("取消") } })
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
