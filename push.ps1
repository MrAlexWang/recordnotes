# 一键推送到 GitHub（首次会弹浏览器让你登录授权）
# 用法：
#   powershell -ExecutionPolicy Bypass -File push.ps1 https://github.com/你的用户名/recordnotes.git
#   或直接双击后按提示输入仓库地址

param(
    [string]$RepoUrl = ""
)

$ErrorActionPreference = "Stop"
$git = "C:\Program Files\Git\cmd\git.exe"
if (-not (Test-Path $git)) {
    $git = "git"
}

if ([string]::IsNullOrWhiteSpace($RepoUrl)) {
    $RepoUrl = Read-Host "请输入仓库地址（例如 https://github.com/abc/recordnotes.git）"
}
if ([string]::IsNullOrWhiteSpace($RepoUrl)) {
    Write-Host "没有输入仓库地址，已取消。" -ForegroundColor Yellow
    exit 1
}

Set-Location $PSScriptRoot

Write-Host "== 1/4 检查仓库 ==" -ForegroundColor Cyan
if (-not (Test-Path ".git")) {
    & $git init -b main | Out-Null
}
& $git config user.name  "RecordNotes"
& $git config user.email "542255492@qq.com"

Write-Host "== 2/4 提交最新改动 ==" -ForegroundColor Cyan
& $git add .
$dirty = (& $git status --porcelain)
if ($dirty) {
    & $git commit -m "update: $(Get-Date -Format 'yyyy-MM-dd HH:mm')" | Out-Null
    Write-Host "已提交本地改动"
} else {
    Write-Host "没有新的改动"
}

Write-Host "== 3/4 设置远程仓库 ==" -ForegroundColor Cyan
$existing = (& $git remote) 2>$null
if ($existing -contains "origin") {
    & $git remote set-url origin $RepoUrl
} else {
    & $git remote add origin $RepoUrl
}
Write-Host "origin -> $RepoUrl"

Write-Host "== 4/4 推送（首次会弹出 GitHub 登录窗口，登录后自动完成）==" -ForegroundColor Cyan
& $git branch -M main
& $git push -u origin main

if ($LASTEXITCODE -eq 0) {
    $web = $RepoUrl -replace "\.git$", ""
    Write-Host ""
    Write-Host "推送成功：$web" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "推送失败。常见原因：" -ForegroundColor Yellow
    Write-Host "  1) 仓库还没创建，先去 GitHub 新建一个空仓库（不要勾选 README）"
    Write-Host "  2) 仓库地址写错，或没有该仓库的写权限"
    Write-Host "  3) 登录窗口被关掉了，重新运行本脚本即可"
}
