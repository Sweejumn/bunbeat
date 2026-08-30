#!/usr/bin/env bash
# RunBPM 一键部署脚本（Ubuntu / Debian）
# 用法：sudo bash deploy/setup.sh
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="${RUNBPM_DATA_DIR:-/opt/runbpm/data}"
SERVICE_USER="runbpm"

echo "==> 应用目录: $APP_DIR"

if [ "$(id -u)" -ne 0 ]; then
  echo "请用 root 运行：sudo bash deploy/setup.sh" >&2
  exit 1
fi

echo "==> 安装系统依赖（python3-venv / pip / ffmpeg）"
apt-get update -y
apt-get install -y python3 python3-venv python3-pip ffmpeg curl

echo "==> 创建 Python 虚拟环境并安装依赖"
cd "$APP_DIR/backend"
if [ ! -d .venv ]; then
  python3 -m venv .venv
fi
./.venv/bin/pip install --upgrade pip
./.venv/bin/pip install -r requirements.txt

echo "==> 检查前端产物 frontend/dist"
if [ ! -d "$APP_DIR/frontend/dist" ]; then
  echo "!! frontend/dist 不存在。" >&2
  echo "   请在本机（Windows）执行 npm.cmd run build 后重新打包上传，再运行本脚本。" >&2
  exit 1
fi

echo "==> 准备数据目录 $DATA_DIR"
mkdir -p "$DATA_DIR"
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  useradd --system --home "$DATA_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
fi
chown -R "$SERVICE_USER:$SERVICE_USER" "$APP_DIR" "$DATA_DIR"

echo "==> 安装 systemd 服务"
sed "s|/opt/runbpm/data|$DATA_DIR|g; s|/opt/runbpm|$APP_DIR|g" \
  "$APP_DIR/deploy/runbpm.service" > /etc/systemd/system/runbpm.service
systemctl daemon-reload
systemctl enable runbpm
systemctl restart runbpm

echo "==> 防火墙：放行 8000 端口（若 ufw 已启用）"
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow 8000/tcp
fi

sleep 2
systemctl --no-pager status runbpm || true
echo ""
echo "=============================================================="
echo " 部署完成！"
echo "   本机验证:  curl http://127.0.0.1:8000/api/health"
echo "   手机访问:  http://<服务器IP>:8000"
echo " 打不开的话，去云厂商控制台（阿里云/腾讯云…）安全组添加入方向规则 TCP 8000。"
echo "=============================================================="
