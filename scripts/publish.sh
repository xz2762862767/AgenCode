#!/usr/bin/env bash
# 一键把本仓库发布到 GitHub。
#
# 用法：
#   GH_TOKEN=ghp_xxx GH_USER=你的用户名 ./scripts/publish.sh
#
# 可选环境变量：
#   REPO=nebula-ai      仓库名（默认 nebula-ai）
#   PRIVATE=false       true 则建私有仓库
#   DESC="..."          仓库描述
#
# 说明：
#   - 只需要一个带 repo 权限的 Personal Access Token
#   - Token 只用于本次请求，不会写进 .git/config（remote 用临时 header 推送）
#   - 用完建议到 GitHub 设置里撤销

set -euo pipefail

: "${GH_TOKEN:?缺少 GH_TOKEN（需要 repo 权限的 Personal Access Token）}"
: "${GH_USER:?缺少 GH_USER（你的 GitHub 用户名）}"

REPO="${REPO:-nebula-ai}"
PRIVATE="${PRIVATE:-false}"
DESC="${DESC:-Android 上的可执行 AI 助手：OpenAI 兼容对话 + 内置 Linux 沙箱与 APK 工程化}"

cd "$(dirname "$0")/.."

api="https://api.github.com"

echo "==> 检查仓库 $GH_USER/$REPO 是否已存在"
code=$(curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "$api/repos/$GH_USER/$REPO")

if [ "$code" = "200" ]; then
  echo "    已存在，直接推送"
elif [ "$code" = "404" ]; then
  echo "    不存在，创建中"
  payload=$(printf '{"name":"%s","description":"%s","private":%s,"has_issues":true,"has_wiki":false}' \
    "$REPO" "$DESC" "$PRIVATE")
  resp=$(curl -s -X POST \
    -H "Authorization: Bearer $GH_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -d "$payload" "$api/user/repos")
  if echo "$resp" | grep -q '"full_name"'; then
    echo "    创建成功"
  else
    echo "    创建失败：$(echo "$resp" | head -c 300)"
    exit 1
  fi
else
  echo "    认证失败（HTTP $code），检查 token 是否有效、是否带 repo 权限"
  exit 1
fi

echo "==> 推送代码"
# 用一次性 header 推送，避免 token 落到 .git/config 里
git -c http.extraheader="Authorization: Bearer $GH_TOKEN" \
    push "https://github.com/$GH_USER/$REPO.git" HEAD:master --force

echo "==> 设置默认分支与远端地址"
curl -s -X PATCH \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d '{"default_branch":"master"}' \
  "$api/repos/$GH_USER/$REPO" > /dev/null

# 远端只写公开地址，不含凭据
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$GH_USER/$REPO.git"

echo
echo "完成 → https://github.com/$GH_USER/$REPO"
echo "记得撤销本次使用的 token。"
