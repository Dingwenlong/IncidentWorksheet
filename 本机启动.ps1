$ErrorActionPreference='Stop'
$runtime=Join-Path $env:LOCALAPPDATA 'WorkorderLab'
$configDir=Join-Path $runtime 'config'
if (!(Test-Path "$configDir/db.xml")) {throw '请先按照 README 配置 MySQL 与应用。'}
$mysqlBase=Join-Path $runtime 'mysql/mysql-8.4.6-winx64'
$java=Get-ChildItem (Join-Path $runtime 'java') -Filter 'zulu*' -Directory | Select-Object -First 1
$jar=Join-Path $PSScriptRoot '发布/workorders.jar'
if (!$java -or !(Test-Path $jar)){throw '缺少本机 Java 或应用包，请参阅 README。'}

function Test-LocalPort([int]$port) {
 $client=New-Object Net.Sockets.TcpClient
 try {$client.Connect('127.0.0.1',$port);return $true} catch {return $false} finally {$client.Dispose()}
}
if (!(Test-LocalPort 13316)) {
 $options=@('--no-defaults',('--basedir="'+$mysqlBase+'"'),('--datadir="'+(Join-Path $runtime 'data')+'"'),'--bind-address=127.0.0.1','--port=13316','--mysqlx=OFF',('--log-error="'+(Join-Path $runtime 'mysql.log')+'"'))
 Start-Process "$mysqlBase/bin/mysqld.exe" -WindowStyle Hidden -ArgumentList $options
}
for ($i=0;$i -lt 60 -and !(Test-LocalPort 13316);$i++){Start-Sleep -Seconds 1}
if (!(Test-LocalPort 13316)){throw '数据库未就绪，请检查本机运行日志。'}

if (!(Test-LocalPort 18080)) {
 $db=Import-Clixml (Join-Path $configDir 'db.xml')
 $admin=Import-Clixml (Join-Path $configDir 'admin.xml')
 $info=New-Object System.Diagnostics.ProcessStartInfo
 $info.FileName=Join-Path $java.FullName 'bin/java.exe'
 $info.Arguments='-Dfile.encoding=UTF-8 -jar workorders.jar'
 $info.WorkingDirectory=Split-Path $jar
 $info.UseShellExecute=$false
 $info.CreateNoWindow=$true
 $info.EnvironmentVariables['DB_USER']=$db.UserName
 $info.EnvironmentVariables['DB_PASSWORD']=$db.GetNetworkCredential().Password
 $info.EnvironmentVariables['DB_URL']='jdbc:mysql://127.0.0.1:13316/workorders?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
 $info.EnvironmentVariables['BOOTSTRAP_ADMIN_USERNAME']=$admin.UserName
 $info.EnvironmentVariables['BOOTSTRAP_ADMIN_PASSWORD']=$admin.GetNetworkCredential().Password
 $demoConfig=Join-Path $configDir 'demo-db.xml'
 if(Test-Path -LiteralPath $demoConfig){
  $demo=Import-Clixml -LiteralPath $demoConfig
  $info.EnvironmentVariables['DEMO_DB_URL']='jdbc:mysql://127.0.0.1:13316/workorders_demo?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
  $info.EnvironmentVariables['DEMO_DB_USER']=$demo.UserName
  $info.EnvironmentVariables['DEMO_DB_PASSWORD']=$demo.GetNetworkCredential().Password
 }
 $info.EnvironmentVariables['APP_PORT']='18080'
 $info.EnvironmentVariables['APP_ADDRESS']='127.0.0.1'
 $info.EnvironmentVariables['LOGGING_FILE_NAME']=Join-Path $runtime 'site.log'
 $site=[Diagnostics.Process]::Start($info)
}
foreach ($i in 1..90) {
 try {
  $token=Invoke-RestMethod 'http://127.0.0.1:18080/api/auth/csrf' -TimeoutSec 3
  if ($token.token){Write-Output 'http://127.0.0.1:18080';return}
 } catch {}
 if ($site -and $site.HasExited){throw '应用未能启动，请检查本机运行目录中的 site.log。'}
 Start-Sleep -Seconds 1
}
throw '应用启动超时。'
