package com.dayoudu.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.InvalidationTracker
import androidx.work.*
import com.dayoudu.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CloudCredentials(context: Context, preferencesName: String="cloud-session",private val alias: String="daily-cloud-token") {
    private val preferences=context.getSharedPreferences(preferencesName,Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(token: String) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        check(preferences.edit().putString("token",Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit())
    }
    fun token(): String? {
        val token=preferences.getString("token",null) ?: return null
        return try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(preferences.getString("iv",null),Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(token,Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) { clear();null }
    }
    fun clear() { preferences.edit().clear().commit() }
}

internal class CloudHttp(private val endpoint: String,private val token: String?=null) {
    class Rejected(val status: Int,val body: JSONObject): Exception(body.optString("error","云端请求失败（$status）"))
    fun call(path: String,body: JSONObject?=null): JSONObject {
        val connection=URI(endpoint+path).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects=false;connection.connectTimeout=15_000;connection.readTimeout=30_000
            connection.requestMethod=if(body==null) "GET" else "POST"
            connection.setRequestProperty("Accept","application/json")
            token?.let { connection.setRequestProperty("Authorization","Bearer $it") }
            if(body!=null){
                val bytes=body.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=1_900_000) { "单条同步数据过大" }
                connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size);connection.outputStream.use { it.write(bytes) }
            }
            val code=connection.responseCode
            val stream=if(code in 200..299) connection.inputStream else connection.errorStream
            val text=stream?.use {
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val count=it.read(buffer);if(count<0)break;require(output.size()+count<=8_000_000){"云端响应过大"};output.write(buffer,0,count)}
                output.toByteArray().toString(Charsets.UTF_8)
            }.orEmpty()
            val result=runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error","云端返回错误（$code）") }
            if(code !in 200..299) throw Rejected(code,result)
            return result
        } catch(e: java.io.IOException) {
            throw java.io.IOException(if(URI(endpoint).scheme=="http")
                "未连上电脑的云端转发端口。请将手机通过 USB 连接电脑并开启 USB 调试，然后在电脑运行“连接手机云端.ps1”；保持连接窗口开启后重试登录。" else
                "连接不到已配置的 HTTPS 云服务，请检查手机网络、域名解析、证书和服务器状态",e)
        } finally { connection.disconnect() }
    }
}

class CloudSync private constructor(private val context: Context) {
    private val database=NutritionDatabase.get(context)
    private val credentials=CloudCredentials(context)
    private val lock=Mutex()
    data class Status(val loggedIn: Boolean=false,val pending: Int=0,val conflicts: List<CloudStore.Conflict> = emptyList(),
        val message: String="未连接云端",val uid: String?=null,val accountName: String?=null)
    val status=MutableStateFlow(Status())
    private var observing=false
    private fun store()=CloudStore(database,context)
    private fun publish(message: String) {
        val store=store(); status.value=Status(credentials.token()!=null,store.count("cloud_dirty"),store.conflicts(),message,
            store.meta("uid"),store.meta("accountName"))
    }
    private fun endpoint(): String {
        val emulator=Build.FINGERPRINT.contains("generic",true)||Build.MODEL.contains("Emulator",true)||Build.PRODUCT.startsWith("sdk_")
        return cloudEndpoint(BuildConfig.CLOUD_API_BASE_URL,BuildConfig.DEBUG,emulator)
    }
    fun start() {
        if(observing)return;observing=true
        database.invalidationTracker.addObserver(object: InvalidationTracker.Observer("plans","plan_revisions","activities","activity_segments","activity_tags","time_categories","projects","ledger_entries","food_versions","intakes","intake_items","nutrition_days") {
            override fun onInvalidated(tables: Set<String>) { schedule() }
        })
        val periodic=PeriodicWorkRequestBuilder<CloudWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily-cloud-periodic",ExistingPeriodicWorkPolicy.KEEP,periodic)
        schedule()
    }
    fun schedule() {
        if(credentials.token()==null)return
        val request=OneTimeWorkRequestBuilder<CloudWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("daily-cloud-sync",ExistingWorkPolicy.KEEP,request)
    }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val token=credentials.token()
        val reachable=token!=null && runCatching {
            CloudHttp(endpoint(),token).call("/api/v1/account/me")
        }.onSuccess { profile -> store().setMeta("uid",profile.getString("uid"));store().setMeta("accountName",profile.getString("accountName")) }.isSuccess
        publish(if(token==null) "未连接云端" else if(reachable) "云端可用，可同步计划、记账和饮食" else "云端暂时不可达；登录状态与本机记录已保留")
    }
    suspend fun login(name: String,password: String) = withContext(Dispatchers.IO) { lock.withLock {
        require(name.isNotBlank()&&password.isNotEmpty()) { "请输入昵称和密码" }
        val endpoint=endpoint()
        val session=CloudHttp(endpoint).call("/api/v1/auth/login",JSONObject().put("accountName",name.trim()).put("password",password))
        acceptSession(session,endpoint)
    } }
    suspend fun register(name: String,password: String) = withContext(Dispatchers.IO) { lock.withLock {
        require(store().meta("owner")==null) { "本机已绑定云端账户，请登录原 UID；新账户请在独立安装上注册" }
        val endpoint=endpoint()
        val session=try { CloudHttp(endpoint).call("/api/v1/auth/register",JSONObject()
            .put("accountName",name.trim()).put("password",password)) }
        catch(e: CloudHttp.Rejected) {
            if(e.status==401||e.status==404) throw IllegalStateException("云服务器尚未升级到支持注册的版本",e)
            throw e
        }
        acceptSession(session,endpoint)
    } }
    private fun acceptSession(session: JSONObject,endpoint: String) {
        val token=session.getString("token")
        val remoteContract=CloudHttp(endpoint,token).call("/api/v1/sync/contract")
        val store=store()
        require(jsonEquivalent(remoteContract,store.localContract())) { "应用与服务器版本不配套，请同时更新服务器部署包和 APK，再重试登录；本机记录已保留" }
        store.bind(session.getString("ownerId"),endpoint)
        store.setMeta("uid",session.getString("uid"));store.setMeta("accountName",session.getString("accountName"))
        credentials.save(token);publish("已连接，准备同步三个模块");schedule()
    }
    suspend fun logout() = withContext(Dispatchers.IO) { lock.withLock {
        val token=credentials.token()
        if(token!=null)runCatching { CloudHttp(endpoint(),token).call("/api/v1/auth/logout",JSONObject()) }
        credentials.clear();publish("已退出登录，本机记录保留")
    } }
    suspend fun renameAccount(name: String) = withContext(Dispatchers.IO) { lock.withLock {
        val token=credentials.token() ?: error("请先登录")
        val result=CloudHttp(endpoint(),token).call("/api/v1/account/name",JSONObject().put("accountName",name))
        store().setMeta("accountName",result.getString("accountName"));publish("账户名已更新")
    } }
    suspend fun resolve(kind: String,id: String,useCloud: Boolean) = withContext(Dispatchers.IO) { lock.withLock {
        store().resolve(kind,id,useCloud);rebuildReminders();publish("已处理冲突，等待同步");schedule()
    } }
    suspend fun retry(kind: String,id: String) = withContext(Dispatchers.IO) { lock.withLock { store().retry(kind,id);publish("已重新加入同步");schedule() } }
    suspend fun sync() = withContext(Dispatchers.IO) { lock.withLock {
        val token=credentials.token() ?: run { publish("未连接云端");return@withLock }
        val store=store();val endpoint=endpoint()
        store.meta("owner")?.let { store.bind(it,endpoint) }
        val http=CloudHttp(endpoint,token)
        try {
            require(jsonEquivalent(http.call("/api/v1/sync/contract"),store.localContract())) {
                "客户端和服务器数据契约不一致，请升级服务器和应用后重试；本机记录已保留"
            }
            publish("正在同步计划、记账和饮食…")
            // Multiple rounds capture edits made while an immutable request was in flight.
            repeat(8) {
                val pending=store.pending()
                for(item in pending) try {
                    store.acknowledge(item,http.call("/api/v1/sync/operations",item.request))
                } catch(e: CloudHttp.Rejected) {
                    if(e.status in setOf(400,409,413,422)){
                        val remote=e.body.optJSONObject("details") ?: JSONObject()
                        store.conflict(item.kind,item.id,remote,e.message ?: "云端校验失败")
                    }else throw e
                }
                if(pending.isEmpty())return@repeat
            }
            var more=true
            while(more) {
                val cursor=store.meta("cursor")?.toLongOrNull() ?: 0
                val page=http.call("/api/v1/sync/changes?cursor=$cursor&limit=5")
                val changes=page.getJSONArray("items")
                for(i in 0 until changes.length())store.acceptChange(changes.getJSONObject(i))
                more=page.getBoolean("hasMore")
            }
            rebuildReminders()
            database.invalidationTracker.refreshVersionsAsync()
            if(store.count("cloud_dirty")>store.count("cloud_conflicts")) throw java.io.IOException("仍有记录待同步，将自动继续")
            publish(if(store.count("cloud_conflicts")==0) "三个模块同步完成" else "部分记录需要处理，请查看冲突")
        } catch(e: Exception) {
            if(e is CloudHttp.Rejected&&e.status==401){credentials.clear();publish("登录已过期，请重新登录");return@withLock}
            publish(e.message ?: "同步暂未完成，本机记录已保留")
            throw e
        }
    } }
    private suspend fun rebuildReminders() {
        val scheduler=ReminderScheduler(context)
        database.openHelper.readableDatabase.query("SELECT id FROM plans").use { cursor ->
            val ids=mutableListOf<String>();while(cursor.moveToNext())ids.add(cursor.getString(0))
            ids.forEach { id -> database.planDao().get(id)?.let(scheduler::sync) }
        }
    }
    companion object {
        @Volatile private var instance: CloudSync?=null
        fun get(context: Context): CloudSync = instance ?: synchronized(this) { instance ?: CloudSync(context.applicationContext).also { instance=it } }
        private fun jsonEquivalent(a: JSONObject,b: JSONObject): Boolean {
            if(a.keys().asSequence().toSet()!=b.keys().asSequence().toSet())return false
            return a.keys().asSequence().all { key -> val x=a.get(key);val y=b.get(key);if(x is JSONObject&&y is JSONObject)jsonEquivalent(x,y) else x.toString()==y.toString() }
        }
    }
}

class CloudWorker(context: Context,parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = try { CloudSync.get(applicationContext).sync();Result.success() } catch (_: Exception) { Result.retry() }
}
