param(
    [string]$Server = 'ubuntu@43.142.121.249',
    [string]$PublicIp = '43.142.121.249'
)

$ErrorActionPreference = 'Stop'
function Set-DirectDeploymentStatus([string]$Phase, [string]$Message) {
    $taskStatusDirectory = Join-Path $PSScriptRoot 'dist'
    New-Item -ItemType Directory -Force -Path $taskStatusDirectory | Out-Null
    $taskStatus = @{
        phase = $Phase
        message = $Message
        endpoint = 'https://' + $PublicIp
        updatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText((Join-Path $taskStatusDirectory 'direct-deploy-status.json'),$taskStatus,[Text.UTF8Encoding]::new($false))
}

function Invoke-LoggedDeploymentCommand([string]$Command, [string[]]$CommandArguments, [string]$LogPath) {
    # Windows PowerShell represents native stderr as ErrorRecord objects. Keep
    # collecting them without aborting on the first line, then check the native
    # exit code in the caller. Password entry remains on SSH's private console.
    $ErrorActionPreference = 'Continue'
    & $Command @CommandArguments 2>&1 |
        ForEach-Object { $_.ToString() } |
        Tee-Object -FilePath $LogPath |
        Out-Host
    return $LASTEXITCODE
}

try {
Set-DirectDeploymentStatus 'checking' 'Checking build tools and deployment parameters.'
if ($Server -notmatch '^[A-Za-z0-9_.-]+@[A-Za-z0-9][A-Za-z0-9.-]*$') {
    throw '服务器格式应为 ubuntu@服务器IP或域名。'
}
if ($PublicIp -notmatch '^([0-9]{1,3}\.){3}[0-9]{1,3}$') {
    throw 'PublicIp 必须是服务器的公网 IPv4 地址。'
}
$taskParsedAddress = $null
if (-not [Net.IPAddress]::TryParse($PublicIp,[ref]$taskParsedAddress)) {
    throw '公网 IPv4 地址无效。'
}
Get-Command ssh.exe -ErrorAction Stop | Out-Null
Get-Command scp.exe -ErrorAction Stop | Out-Null
Get-Command curl.exe -ErrorAction Stop | Out-Null

$taskHttpsOrigin = 'https://' + $PublicIp
Write-Host "将手机云端地址配置为 $taskHttpsOrigin。此过程不需要连接 USB 手机。"
Write-Host '先构建安装包及配套后端；随后通过 SSH 完成一次服务器部署。'

Set-DirectDeploymentStatus 'building' 'Building direct APK and server bundle.'
& (Join-Path $PSScriptRoot '生成APK.ps1') -CloudApiBaseUrl $taskHttpsOrigin
& (Join-Path $PSScriptRoot 'deploy/build-bundle.ps1')
$taskApkSource = Join-Path $PSScriptRoot 'dist/apk/dayoudu-debug.apk'
$taskDirectApk = Join-Path $PSScriptRoot 'dist/apk/dayoudu-direct.apk'
Copy-Item -LiteralPath $taskApkSource -Destination $taskDirectApk -Force

Write-Host '请确认腾讯云轻量服务器防火墙已允许入站 TCP 80 和 443。'
Write-Host '接下来若提示密码，请在本终端输入 Ubuntu SSH 密码，不要发到聊天。'
Write-Host '上传与部署可能分别要求输入一次密码。'
$taskCloudBundle = Join-Path $PSScriptRoot 'dist/cloud-release'
$taskUploadLog = Join-Path $PSScriptRoot 'dist/direct-upload.log'
$taskDeploymentLog = Join-Path $PSScriptRoot 'dist/direct-deploy.log'
Set-DirectDeploymentStatus 'uploading' 'Uploading release; enter SSH password in the deployment terminal if requested.'
$taskUploadExitCode = Invoke-LoggedDeploymentCommand -Command 'scp.exe' -CommandArguments @('-r',$taskCloudBundle,($Server + ':~/')) -LogPath $taskUploadLog
if ($taskUploadExitCode -ne 0) { throw "部署包上传失败（退出码 $taskUploadExitCode）。连接错误已保存到 $taskUploadLog；尚未进入 HTTPS 配置阶段。" }

$taskRemoteCommand = "cp -R ~/cloud-release/. ~/daily-rhythm/ && cd ~/daily-rhythm && bash -o pipefail -c 'bash start-direct.sh " + $PublicIp + " 2>&1 | tee direct-deploy.log'"
Set-DirectDeploymentStatus 'deploying' 'Deploying HTTPS on Ubuntu; enter SSH password in the deployment terminal if requested.'
# SSH reads its password privately from the console. Command output and SSH
# errors are logged; no terminal transcript or password input is recorded.
$taskDeployExitCode = Invoke-LoggedDeploymentCommand -Command 'ssh.exe' -CommandArguments @('-t',$Server,$taskRemoteCommand) -LogPath $taskDeploymentLog
if ($taskDeployExitCode -eq 255) {
    throw "SSH 连接失败（退出码 255），错误已保存到 $taskDeploymentLog。请按具体错误检查连接、认证或远程会话；此错误不能直接归因为 HTTPS 防火墙。"
}
if ($taskDeployExitCode -ne 0) {
    throw "云端部署失败（退出码 $taskDeployExitCode），尚未确认 HTTPS 可用。部署输出已保存到 $taskDeploymentLog；服务器也保存为 ~/daily-rhythm/direct-deploy.log。"
}

# Verify from the actual public network after SSH has exited. Never disable certificate checks.
Write-Host 'SSH 部署连接已结束，开始从公网验证 HTTPS。'
Set-DirectDeploymentStatus 'verifying' 'Checking public HTTPS after SSH deployment connection has exited.'
$taskHealthOutput = & curl.exe --fail --silent --show-error --connect-timeout 5 --max-time 20 ($taskHttpsOrigin + '/health')
if ($LASTEXITCODE -ne 0) { throw '公网 HTTPS 健康检查失败，请检查云防火墙和证书；尚不能确认手机直连可用。' }
$taskHealth = $taskHealthOutput | ConvertFrom-Json
if ($taskHealth.status -ne 'ok') { throw '云端健康检查没有返回 ok。' }
$taskUnauthorizedCode = & curl.exe --silent --show-error --connect-timeout 5 --max-time 15 -o NUL -w '%{http_code}' ($taskHttpsOrigin + '/api/v1/account/me')
if ($LASTEXITCODE -ne 0 -or $taskUnauthorizedCode -ne '401') {
    throw '账户接口访问校验未通过，尚未确认直连部署成功。'
}

Write-Host ''
Write-Host "公网 HTTPS 验证通过：$taskHttpsOrigin"
Write-Host "请把此 APK 复制到手机覆盖安装：$taskDirectApk"
Write-Host '安装后可拔掉 USB、关闭 SSH 窗口和电脑，使用手机 Wi-Fi 或流量登录原账户并同步。'
Write-Host '当前个人云服务默认关闭新账户注册，已有账户保持可用。'
Set-DirectDeploymentStatus 'complete' $taskDirectApk
} catch {
    Set-DirectDeploymentStatus 'failed' $_.Exception.Message
    throw
}
