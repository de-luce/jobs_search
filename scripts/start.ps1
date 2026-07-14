# Get Jobs 一键启动（Windows）：建库（如需）→ 装前端依赖 → 启动后端（后端会自动拉起 Vue）
# 用法：
#   .\scripts\start.ps1
#   .\scripts\start.ps1 --init-db
#   .\scripts\start.bat              （双击或 cmd 调用，内部转本脚本）

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = (Resolve-Path (Join-Path $ScriptDir '..')).Path

$DbDir = Join-Path $Root 'db'
$DbFile = Join-Path $DbDir 'getjobs.db'
$Schema = Join-Path $Root 'src\main\resources\db\schema.sql'
$FrontDir = Join-Path $Root 'front'

$InitDbOnly = $false

foreach ($arg in $args) {
    switch ($arg) {
        '--init-db' { $InitDbOnly = $true }
        { $_ -in '-h', '--help' } {
            Write-Host @'
Get Jobs 一键启动（Windows）
  .\scripts\start.ps1                 正常启动
  .\scripts\start.ps1 --init-db       仅初始化数据库后退出
'@
            exit 0
        }
        default {
            Write-Error "未知参数: $arg（支持 --init-db）"
            exit 1
        }
    }
}

function Write-Log([string]$Message) {
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Die([string]$Message) {
    Write-Host "[error] $Message" -ForegroundColor Red
    exit 1
}

function Need-Cmd([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Write-Die "未找到命令: $Name（请安装并加入 PATH）"
    }
}

function Check-Deps {
    Need-Cmd 'java'
    Need-Cmd 'mvn'
    if (-not $InitDbOnly) {
        Need-Cmd 'npm'
    }
    if (-not (Test-Path $DbFile)) {
        Need-Cmd 'sqlite3'
    }
}

function Init-Db {
    if (-not (Test-Path $DbDir)) {
        New-Item -ItemType Directory -Path $DbDir | Out-Null
    }
    if (Test-Path $DbFile) {
        Write-Log "数据库已存在: $DbFile（跳过建库）"
        return
    }
    if (-not (Test-Path $Schema)) {
        Write-Die "缺少 schema: $Schema"
    }
    Write-Log "初始化空库: $DbFile"
    Get-Content -Path $Schema -Raw | sqlite3 $DbFile
    Write-Log "建库完成（仅结构 + 字典种子，无个人数据）"
}

function Ensure-Front {
    $nodeModules = Join-Path $FrontDir 'node_modules'
    if (-not (Test-Path $nodeModules)) {
        Write-Log '安装前端依赖...'
        Push-Location $FrontDir
        try {
            npm install
        } finally {
            Pop-Location
        }
    }
}

function Start-Backend {
    Write-Log '启动后端: mvn spring-boot:run'
    Write-Log '管理页默认 http://127.0.0.1:6866 （后端会尝试自动拉起 Vue 并打开）'
    Write-Log '按 Ctrl+C 结束时会一并关闭由后端拉起的前端'
    Set-Location $Root
    mvn -q spring-boot:run
}

Set-Location $Root
Check-Deps
Init-Db

if ($InitDbOnly) {
    Write-Log '仅建库模式，结束'
    exit 0
}

Ensure-Front
Start-Backend
