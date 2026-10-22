param(
    [string]$DeviceId = '',
    [string]$Server = 'ubuntu@43.142.121.249',
    [switch]$UpdateServer
)

$ErrorActionPreference = 'Stop'
Get-Command adb.exe -ErrorAction Stop | Out-Null
Get-Command ssh.exe -ErrorAction Stop | Out-Null
if ($Server -notmatch '^[A-Za-z0-9_.-]+@[A-Za-z0-9][A-Za-z0-9.-]*$') {
    throw '服务器格式应为 ubuntu@服务器IP或域名。'
}

$taskDeviceLines = & adb.exe devices
if ($LASTEXITCODE -ne 0) { throw '无法检查手机，请确认已安装 Android Platform-Tools。' }
$taskDeviceIds = @($taskDeviceLines | ForEach-Object {
    if ($_ -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
})
if ($DeviceId) {
    if ($DeviceId -notin $taskDeviceIds) { throw "未识别到设备 $DeviceId，请连接 USB 并允许调试。" }
    $taskSelectedDevice = $DeviceId
} else {
    if ($taskDeviceIds.Count -eq 0) {
        throw '尚未识别到手机。请用 USB 连接手机，开启开发者选项中的 USB 调试，并在手机上允许本电脑调试，然后重新运行本脚本。'
    }
    if ($taskDeviceIds.Count -gt 1) {
        throw ('发现多台设备，请用 -DeviceId 指定一台：' + ($taskDeviceIds -join ', '))
    }
    $taskSelectedDevice = $taskDeviceIds[0]
}

& adb.exe -s $taskSelectedDevice reverse tcp:18080 tcp:18080
if ($LASTEXITCODE -ne 0) { throw '手机端口转发失败，请重新连接 USB 后重试。' }
Write-Host "已配置手机端口转发：$taskSelectedDevice"

if ($UpdateServer) {
    Get-Command scp.exe -ErrorAction Stop | Out-Null
    $taskCloudBundle = Join-Path $PSScriptRoot 'dist/cloud-release'
    if (-not (Test-Path -LiteralPath (Join-Path $taskCloudBundle 'SHA256SUMS'))) {
        throw '未找到云端部署包，请先运行 deploy/build-bundle.ps1。'
    }
    Write-Host '正在上传配套后端。接下来可能多次要求输入服务器 SSH 密码，请在本窗口输入。'
    & scp.exe -r $taskCloudBundle ($Server + ':~/')
    if ($LASTEXITCODE -ne 0) { throw '部署包上传失败，尚未升级服务器。' }
    Write-Host '开始服务器备份和升级。请等待 api/db 均为 healthy。'
    & ssh.exe -t $Server 'cp -R ~/cloud-release/. ~/daily-rhythm/ && cd ~/daily-rhythm && bash start-api.sh'
    if ($LASTEXITCODE -ne 0) { throw '服务器升级未完成。请查看上方报错，当前未继续连接登录。' }
}

$taskAlreadyConnected = $false
try {
    $taskHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/health' -TimeoutSec 3
    $taskAlreadyConnected = $taskHealth.status -eq 'ok'
} catch { }
if ($taskAlreadyConnected) {
    $taskExistingListeners = @(Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue)
    $taskSshListeners = @($taskExistingListeners | Where-Object {
        (Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName -eq 'ssh'
    })
    if ($taskSshListeners.Count -eq 0) {
        throw '电脑 18080 端口有服务响应，但未确认是 SSH 隧道。请检查是否仍运行本地测试服务器；本脚本没有停止任何进程。'
    }
    Write-Host '电脑的云端连接已经可用。请保持现有隧道窗口与 USB 连接，在手机重新登录。'
    exit 0
}
if (Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue) {
    throw '电脑 18080 端口已被占用，但健康检查未通过。请先确认原隧道或服务状态，再重试；本脚本没有停止任何进程。'
}

Write-Host '接下来会连接 Ubuntu。若提示密码，请在本窗口输入服务器 SSH 密码；输入时不显示字符。'
Write-Host '连接成功并显示 status:ok 后，在手机打开日有度 → 我的 → 账户与同步，重试登录。'
Write-Host '使用期间保持此窗口和 USB 连接。输入 exit 或关闭窗口会结束隧道。'
$taskRemoteCheck = "curl --fail --silent --show-error --max-time 10 http://127.0.0.1:18080/health && printf '\nCloud API reachable. Keep this window open and retry login on your phone.\n' && exec bash -l"
& ssh.exe -t -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 `
    -L '127.0.0.1:18080:127.0.0.1:18080' $Server $taskRemoteCheck
if ($LASTEXITCODE -ne 0) {
    throw 'SSH 或服务器健康检查失败。请查看上方错误；若为服务器 API 未启动，请先按运行指南部署后端。'
}
