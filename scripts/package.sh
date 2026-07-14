#!/usr/bin/env bash
# 打包可分发目录：前端静态资源 + fat jar + 启动脚本
# 用法：./scripts/package.sh
# 产物：release/getjobs/ （解压/拷贝后双击 start 即可，只需 JDK 21+ 与本机 Chrome）

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RELEASE_DIR="$ROOT/release/getjobs"
FRONT_DIR="$ROOT/front"
JAR_GLOB="target/jobs_search-*.jar"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "未找到命令: $1"
}

need_cmd java
need_cmd mvn
need_cmd npm

log "构建前端并复制到 resources/dist ..."
(cd "$FRONT_DIR" && npm install && npm run build:prod)

log "Maven 打包 fat jar ..."
mvn -q -DskipTests package

JAR="$(ls -1 $JAR_GLOB 2>/dev/null | grep -v '\.original$' | head -1 || true)"
[[ -n "$JAR" && -f "$JAR" ]] || die "未找到产物 jar: $JAR_GLOB"

log "组装分发目录: $RELEASE_DIR"
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR/db" "$RELEASE_DIR/logs"

cp "$JAR" "$RELEASE_DIR/getjobs.jar"
cp "$ROOT/scripts/release/start.sh" "$RELEASE_DIR/start.sh"
cp "$ROOT/scripts/release/start.bat" "$RELEASE_DIR/start.bat"
cp "$ROOT/scripts/release/start.ps1" "$RELEASE_DIR/start.ps1"
cp "$ROOT/scripts/release/README.txt" "$RELEASE_DIR/README.txt"
chmod +x "$RELEASE_DIR/start.sh"

# 可选：打 zip
if command -v zip >/dev/null 2>&1; then
  ZIP="$ROOT/release/getjobs.zip"
  rm -f "$ZIP"
  (cd "$ROOT/release" && zip -qr getjobs.zip getjobs)
  log "已生成压缩包: $ZIP"
fi

log "完成。将 release/getjobs 拷到目标机器后执行 start.sh / start.bat"
log "首次启动会自动创建 db/getjobs.db，并用 Chrome 打开管理页 http://127.0.0.1:6866"
