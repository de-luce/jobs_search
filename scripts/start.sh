#!/usr/bin/env bash
# Get Jobs 一键启动：建库（如需）→ 装前端依赖 → 启动后端（后端会自动拉起 Vue）
# 用法：
#   ./scripts/start.sh           # 正常启动
#   ./scripts/start.sh --init-db # 仅初始化数据库后退出

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DB_DIR="$ROOT/db"
DB_FILE="$DB_DIR/getjobs.db"
SCHEMA="$ROOT/src/main/resources/db/schema.sql"
FRONT_DIR="$ROOT/front"

INIT_DB_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --init-db) INIT_DB_ONLY=1 ;;
    -h|--help)
      sed -n '2,6p' "$0"
      exit 0
      ;;
    *)
      echo "未知参数: $arg（支持 --init-db）" >&2
      exit 1
      ;;
  esac
done

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "未找到命令: $1"
}

check_deps() {
  need_cmd java
  need_cmd mvn
  if [[ "$INIT_DB_ONLY" -eq 0 ]]; then
    need_cmd npm
  fi
  if [[ ! -f "$DB_FILE" ]]; then
    need_cmd sqlite3
  fi
}

init_db() {
  mkdir -p "$DB_DIR"
  if [[ -f "$DB_FILE" ]]; then
    log "数据库已存在: $DB_FILE（跳过建库）"
    return 0
  fi
  [[ -f "$SCHEMA" ]] || die "缺少 schema: $SCHEMA"
  log "初始化空库: $DB_FILE"
  sqlite3 "$DB_FILE" < "$SCHEMA"
  log "建库完成（仅结构 + 字典种子，无个人数据）"
}

ensure_front() {
  if [[ ! -d "$FRONT_DIR/node_modules" ]]; then
    log "安装前端依赖..."
    (cd "$FRONT_DIR" && npm install)
  fi
}

start_backend() {
  log "启动后端: mvn spring-boot:run"
  log "管理页默认 http://127.0.0.1:6866 （后端会尝试自动拉起 Vue 并打开）"
  log "按 Ctrl+C 结束时会一并关闭由后端拉起的前端"
  mvn -q spring-boot:run
}

main() {
  check_deps
  init_db
  if [[ "$INIT_DB_ONLY" -eq 1 ]]; then
    log "仅建库模式，结束"
    exit 0
  fi
  ensure_front
  start_backend
}

main
