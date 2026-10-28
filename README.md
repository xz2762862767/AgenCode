# AgenCode（Android AI 智能体）

![Version](https://img.shields.io/badge/version-1.0.0-blue)
![License](https://img.shields.io/badge/license-MIT-blue)
![Platform](https://img.shields.io/badge/Android-8.0%2B-green)
![minSdk](https://img.shields.io/badge/minSdk-26-lightgrey)

一个跑在 Android 手机上的 AI 助手，把「能聊天」和「能干活」放在同一个 App 里：
接任意 OpenAI 兼容接口，同时内置一套可被模型调用的本地执行环境
（Linux shell、APK 反编译/回编译、技能包、MCP 工具）。

不用电脑、不用 ADB，装上就是一个随身的工作站。

---

## 安装

**① 装现成的包**：到 [Releases](../../releases) 下载最新的 `NebulaAI-v*.apk`，
手机上允许「安装未知来源应用」后点开即可。最低支持 Android 8.0（API 26）。

**② 自己编译**：见下方[构建](#构建)，产物在 `app/build/outputs/apk/debug/`。

装好后进「设置」填 API 地址、Key、模型名，就能开始对话。

---

## 主要能力

### 对话
- 任意 OpenAI 兼容后端：`baseURL` / `key` / `model` 全部可在设置里改，
  兼容 `/v1/chat/completions` 与 `GET /v1/models`
- SSE 流式输出，逐字上屏；支持中途停止、重新生成、复制
- 多会话历史，本地持久化（切后台、杀进程都不丢）
- Markdown 渲染：标题、列表、表格、引用、行内代码
- 代码块带语言标签、行号、一键复制、长按更多操作
- 中文解释行的重点标记高亮（路径、命令、错误分别取色）
- 每轮回答可附带「结论」卡片，正文自动剥离协议标记

### 工具调用（Function Calling）
模型可以主动调用本地能力，不是只给建议：

| 分组 | 能力 |
|---|---|
| Shell | 在 App 内置 Linux 里执行命令，读写文件，跑脚本 |
| APK | 反编译、改资源/清单、回编译、对齐、签名、安装 |
| 构建 | 目录内编译打包，产物自动归档 |
| 联网 | 搜索、抓取网页正文 |

执行过程会实时显示成「步骤卡」：每一步做什么、结果如何、耗时多少，
失败会标红并保留输出，方便定位。

### 内置 Linux 环境
- 首次使用一键安装（Alpine + proot），装完即可用 `sh` / `python` / `apk`
- 模型看到的沙箱路径与提示词描述一致，上传的文件在两个环境下都能访问
- 环境页可查看占用、重建、卸载

### APK 工程化
- 反编译 APK → 在「工程」目录里直接改 smali / 资源 / 清单 → 一键回编译签名
- 自动修 `AndroidManifest.xml` 常见冲突（权限、`exported`、`debuggable`）
- 产物统一进 `/sdcard/AgenCode` 目录（老版本名为「星云产物」，升级后就地改名），按 `APK / 工程 / 日志` 分类
- 有存储权限时直接写公共目录，没有就走 MediaStore 或应用私有目录
- 面板里可以打开或复制路径、导出整个工程、导入外部签名密钥

### 扩展
- **技能（Skills）**：导入 zip，启用/禁用，按需注入系统提示词
- **MCP**：填服务器地址即可把外部工具挂进来，和内置工具一起被调度

### 稳定性
- `CrashLog`：崩溃堆栈、ANR 记录、心跳全部落盘到 `下载/NebulaAI/`
- `AnrWatchdog`：主线程卡顿超阈值自动记录调用栈
- 历史消息虚拟化 + 流式渲染让路，长会话滑动不掉帧

### 状态可视化

正文下方的状态条用颜色区分阶段，不必读文案也能判断：

| 颜色 | 含义 |
|---|---|
| 亮绿 | 正在执行（图标旋转，底部光条流动） |
| 青蓝 | 本轮已结束，显示总用时 |
| 暖橙 | 被中断或失败 |

运行横幅的文字与计时数字同样跟随状态变色。

---

## 构建

**环境要求**：JDK 17、Android SDK（API 34 + build-tools 34.0.0）、Gradle 8.x。
项目不含 wrapper，用系统 Gradle。

```bash
# 调试包
gradle assembleDebug --no-daemon -Dorg.gradle.vfs.watch=false

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

### 发布包签名

仓库里**不包含任何密钥**。要出 release 包，自己在项目根目录放一个
`keystore.properties`（该文件与 `keystore/` 都已在 `.gitignore` 中）：

```properties
storeFile=keystore/your-release.jks
storePassword=******
keyAlias=******
keyPassword=******
```

生成密钥：

```bash
keytool -genkeypair -v -keystore keystore/your-release.jks \
  -alias youralias -keyalg RSA -keysize 2048 -validity 10000
```

也支持用环境变量覆盖（适合 CI）：
`NEBULA_KEYSTORE` / `NEBULA_STORE_PASSWORD` / `NEBULA_KEY_ALIAS` / `NEBULA_KEY_PASSWORD`。

没有配置密钥时，`assembleRelease` 不会挂签名，出的是未签名包 —— 本地只做功能验证够用。

```bash
gradle assembleRelease --no-daemon -Dorg.gradle.vfs.watch=false
```

---

## 配置

首次启动后进「设置」：

1. **API 地址**：填 OpenAI 兼容的基础地址，例如 `https://api.openai.com/v1`
2. **API Key**：填自己的密钥，只存在本机，不上传
3. **模型**：点「拉取模型」从 `GET /models` 拿列表，也可以手动加一个名字
4. **系统提示词**：想让它扮演什么角色、用什么语气，都写这里

温度、上下文轮数、是否自动执行工具等都在同一个页面。

---

## 项目结构

```
app/src/main/java/com/nebula/aichat/
├── MainActivity.java        主界面：会话、流式渲染、工具卡片
├── data/                    会话与设置持久化（ChatStore / Message / Settings）
├── net/                     网络层（ChatClient 流式、ModelListClient、BalanceClient、WebSearch）
├── tool/                    工具注册表与各 Provider
├── shell/                   内置 shell 执行
├── linux/                   Linux 环境安装与工具链
├── apk/                     APK 反编译、回编译、签名、产物归档
├── skill/                   技能包导入与启用
├── mcp/                     MCP 客户端与配置
├── ui/                      自绘组件（结论卡、步骤卡、Markdown、长按菜单等）
└── util/                    CrashLog、AnrWatchdog、JsonOpt、TaskParser
```

界面全部用系统控件与自绘 `View`，不依赖第三方 UI 库，安装包轻。

---

## 已知限制

- 内置 Linux 依赖 proot，部分机型（内核限制）可能无法运行，App 会给出提示
- APK 回编译需要 aapt2/d8，首次使用要等工具链就绪
- 只支持 OpenAI 兼容协议，不兼容各家私有格式

## 参与贡献

- **报 bug**：附上机型和 `下载/NebulaAI/` 目录下的崩溃日志，比截图有用得多
- **提 PR**：一个 PR 只做一件事；改动较大时先开 Issue 对齐方向
- **提交前**：跑一次 `gradle assembleDebug` 确认能编过

## 许可

MIT，见 [LICENSE](LICENSE)。

第三方组件（Alpine rootfs、proot、talloc 等）遵循其各自许可，位于 `env-assets/`。
