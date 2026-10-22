package com.dayoudu.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayoudu.app.data.CloudSync
import com.dayoudu.app.data.LocalUserRepository
import kotlinx.coroutines.launch

@Composable
fun CloudPanel(userRepository: LocalUserRepository, localName: String?) {
    val context=LocalContext.current
    val cloud=remember { CloudSync.get(context) }
    val status by cloud.status.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var loginName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var showNameEditor by remember { mutableStateOf(false) }
    var accountName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf<Triple<String,String,Boolean>?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var createdUid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { cloud.refresh() }
    LaunchedEffect(status.loggedIn,status.accountName) {
        if(status.loggedIn && !status.accountName.isNullOrBlank() && localName!=status.accountName) {
            runCatching { userRepository.ensure();userRepository.rename(status.accountName!!) }
        }
    }
    fun launch(action: suspend () -> Unit) {
        busy=true;error=null
        scope.launch { try { action() } catch(e: Exception){error=e.message ?: "操作失败"} finally { busy=false } }
    }
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Mist)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("个人资料",color=Pine,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
                    Text(if(status.loggedIn) status.accountName ?: localName ?: "我的日有度" else localName ?: "我的日有度",
                        color=Ink,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text(if(status.loggedIn) "已登录云账户" else "本机使用",color=MutedInk,style=MaterialTheme.typography.bodySmall)
                }
                Image(painterResource(R.drawable.diary_sprout),contentDescription=null,
                    modifier=Modifier.size(88.dp),contentScale=ContentScale.Fit)
            }
            HorizontalDivider()
            if(!status.loggedIn) {
                Text("登录后同步计划、记账和饮食；也可继续只在本机使用。",style=MaterialTheme.typography.bodySmall,color=MutedInk)
                if(BuildConfig.DEBUG) Text(if(BuildConfig.CLOUD_API_BASE_URL.isBlank())
                    "当前连接方式：电脑中转。手机需开启 USB 调试，在电脑运行“连接手机云端.ps1”，保持窗口开启后登录。" else
                    "当前连接方式：HTTPS 云端直连。手机联网后可登录与同步，无需电脑转发。",
                    style=MaterialTheme.typography.bodySmall,color=MutedInk)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Button(onClick={loginName="";password="";error=null;authMode="login"},enabled=!busy){Text("登录")}
                    OutlinedButton(onClick={loginName="";password="";accountName=localName.orEmpty();error=null;authMode="register"},enabled=!busy){Text("创建账户")}
                }
                TextButton(onClick={accountName=localName.orEmpty();showNameEditor=true},enabled=!busy){Text("编辑昵称")}
            } else {
                status.uid?.let { androidx.compose.foundation.text.selection.SelectionContainer {
                    Text("UID：$it",style=MaterialTheme.typography.bodySmall,color=MutedInk)
                } }
                Text(status.message,style=MaterialTheme.typography.bodySmall)
                Text("待同步 ${status.pending} 项 · 待处理 ${status.conflicts.size} 项",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={launch { cloud.sync() }},enabled=!busy){Text("立即同步")}
                    TextButton(onClick={launch { cloud.logout() }},enabled=!busy){Text("退出登录")}
                }
                TextButton(onClick={accountName=status.accountName.orEmpty();showNameEditor=true},enabled=!busy){Text("编辑昵称")}
            }
            status.conflicts.forEach { conflict ->
                HorizontalDivider()
                val name=when(conflict.kind){"plan"->"计划";"activity"->"活动";"ledger"->"账目";"food"->"食物";"intake"->"摄入";"nutritionDay"->"当日营养目标";"category"->"分类";else->"项目"}
                Text("$name：${conflict.id.take(8)}",style=MaterialTheme.typography.labelLarge)
                Text(conflict.message,style=MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick={preview=conflict.localPreview}){Text("查看本机")}
                    if(conflict.hasRemote)TextButton(onClick={preview=conflict.remotePreview}){Text("查看云端")}
                }
                Row {
                    if(conflict.hasRemote)TextButton(onClick={choice=Triple(conflict.kind,conflict.id,true)},enabled=!busy){Text("保留云端")}
                    if(conflict.hasRemote&&conflict.kind!="food")TextButton(onClick={choice=Triple(conflict.kind,conflict.id,false)},enabled=!busy){Text("保留本机")}
                    TextButton(onClick={launch { cloud.retry(conflict.kind,conflict.id) }},enabled=!busy){Text("修正后重试")}
                }
            }
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    }
    if(authMode!=null) AlertDialog(onDismissRequest={if(!busy)authMode=null},title={Text(if(authMode=="register") "创建账户" else "登录账户")},
        text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(if(authMode=="register") "填写昵称和密码；昵称可以重复，系统会另发唯一 UID。" else "使用昵称和密码登录。同名且密码相同的账户需改用 UID。",style=MaterialTheme.typography.bodyMedium)
            if(authMode=="login") OutlinedTextField(loginName,{loginName=it},label={Text("昵称或 UID")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(authMode=="register") OutlinedTextField(accountName,{accountName=it},label={Text("昵称")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(password,{password=it},label={Text("密码")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                visualTransformation=if(showPassword)VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon={TextButton(onClick={showPassword=!showPassword}){Text(if(showPassword)"隐藏" else "显示")}},
                modifier=Modifier.fillMaxWidth())
            if(authMode=="register") Text("密码 8–16 位 ASCII 字符，不含空格、引号、$、#、反斜杠。",style=MaterialTheme.typography.bodySmall)
            if(busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if(authMode=="register") "正在创建账户…" else "正在登录…",style=MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }},
        confirmButton={Button(onClick={launch {
            if(authMode=="register") {
                cloud.register(accountName,password)
                createdUid=cloud.status.value.uid
            } else cloud.login(loginName,password)
            userRepository.ensure();userRepository.rename(cloud.status.value.accountName.orEmpty())
            password="";authMode=null;cloud.sync()
        }},enabled=!busy&&password.isNotEmpty()&&(authMode=="register"&&accountName.isNotBlank()||authMode=="login"&&loginName.isNotBlank())){Text(if(authMode=="register") "创建并登录" else "登录")}},
        dismissButton={TextButton(onClick={authMode=null},enabled=!busy){Text("取消")}})
    createdUid?.let { issued -> AlertDialog(onDismissRequest={createdUid=null},title={Text("账户已创建")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("账户已创建，以后可用昵称和密码登录。请保存这串 UID；同名同密码或找回账户时可用它区分。")
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(issued,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
            }
        }},confirmButton={TextButton(onClick={createdUid=null}){Text("已记下")}}) }
    if(showNameEditor) AlertDialog(onDismissRequest={showNameEditor=false},title={Text("编辑昵称")},
        text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(accountName,{accountName=it},label={Text(if(status.loggedIn) "云端和本机昵称" else "本机昵称")},singleLine=true)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }},
        confirmButton={TextButton(onClick={launch {
            if(status.loggedIn) cloud.renameAccount(accountName)
            userRepository.ensure();userRepository.rename(accountName);showNameEditor=false
        }},enabled=!busy){Text("保存")}},
        dismissButton={TextButton(onClick={showNameEditor=false},enabled=!busy){Text("取消")}})
    preview?.let { value -> AlertDialog(onDismissRequest={preview=null},title={Text("记录内容")},
        text={ androidx.compose.foundation.text.selection.SelectionContainer {
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) { Text(value,style=MaterialTheme.typography.bodySmall) }
        } },confirmButton={TextButton(onClick={preview=null}){Text("关闭")}}) }
    choice?.let { (kind,id,useCloud) ->
        AlertDialog(onDismissRequest={choice=null},title={Text(if(useCloud)"保留云端版本？" else "保留本机版本？")},
            text={Text(if(useCloud)"将用该条云端记录替换本机对应记录及关联明细。请先导出备份。" else "将按最新云端版本重新提交该条本机记录。提交前仍会检查关联与业务规则。")},
            confirmButton={TextButton(onClick={choice=null;launch { cloud.resolve(kind,id,useCloud) }}){Text("确认")}},
            dismissButton={TextButton(onClick={choice=null}){Text("取消")}})
    }
}
