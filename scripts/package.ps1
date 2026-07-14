# Get Jobs 可分发打包（Windows）
# 用法：.\scripts\package.ps1
# 产物：release\getjobs\

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = (Resolve-Path (Join-Path $ScriptDir '..')).Path
$ReleaseDir = Join-Path $Root 'release\getjobs'
$FrontDir = Join-Path $Root 'front'

function Write-Log([string]$Message) {
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Die([string]$Message) {
    Write-Host "[error] $Message" -ForegroundColor Red
    exit 1
}

foreach ($cmd in @('java', 'mvn', 'npm')) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Die "未找到命令: $cmd"
    }
}

Set-Location $Root

Write-Log '构建前端并复制到 resources/dist ...'
Push-Location $FrontDir
try {
    npm install
    npm run build:prod
} finally {
    Pop-Location
}

Write-Log 'Maven 打包 fat jar ...'
mvn -q -DskipTests package

$jar = Get-ChildItem -Path (Join-Path $Root 'target') -Filter 'jobs_search-*.jar' |
    Where-Object { $_.Name -notlike '*.original' } |
    Select-Object -First 1
if (-not $jar) {
    Write-Die '未找到产物 jar'
}

Write-Log "组装分发目录: $ReleaseDir"
if (Test-Path $ReleaseDir) {
    Remove-Item -Recurse -Force $ReleaseDir
}
New-Item -ItemType Directory -Path (Join-Path $ReleaseDir 'db') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $ReleaseDir 'logs') | Out-Null

Copy-Item $jar.FullName (Join-Path $ReleaseDir 'getjobs.jar')
Copy-Item (Join-Path $Root 'scripts\release\start.sh') (Join-Path $ReleaseDir 'start.sh')
Copy-Item (Join-Path $Root 'scripts\release\start.bat') (Join-Path $ReleaseDir 'start.bat')
Copy-Item (Join-Path $Root 'scripts\release\start.ps1') (Join-Path $ReleaseDir 'start.ps1')
Copy-Item (Join-Path $Root 'scripts\release\README.txt') (Join-Path $ReleaseDir 'README.txt')

$zipPath = Join-Path $Root 'release\getjobs.zip'
if (Test-Path $zipPath) {
    Remove-Item -Force $zipPath
}
Compress-Archive -Path $ReleaseDir -DestinationPath $zipPath -Force

Write-Log "完成。产物: $ReleaseDir ；压缩包: $zipPath"
Write-Log '目标机器只需 JDK 21+ 与 Chrome，双击 start.bat 即可'
