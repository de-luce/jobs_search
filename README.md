# Get Jobs

本机运行的多平台自动求职工具：通过 Web 管理页配置筛选条件，用 Playwright 在本地浏览器中完成登录与投递。

支持平台：**Boss 直聘**、**猎聘**、**智联招聘**、**前程无忧（51job）**。

> 仅限本机使用，不支持服务器部署。招聘站点会对机房 IP 做限制。

## 功能

- Web 管理界面：环境配置、AI 配置、全局黑名单、各平台投递配置与岗位分析
- Playwright 自动化：管理页与招聘站同窗标签页操作，扫码登录后 Cookie 持久化
- 按关键词 / 城市 / 薪资等条件筛选并自动发起沟通
- 黑名单企业过滤；Boss 支持不活跃 HR 过滤、可选图片简历与 AI 打招呼语
- 投递进度 SSE 推送；可选企业微信机器人通知（`HOOK_URL`）

## 环境要求

| 依赖 | 说明 |
|------|------|
| JDK 21 | 后端运行 |
| Maven 3.9+ | 构建 / 启动 |
| Node.js 18+ | 前端开发模式（可选；也可用已构建的静态资源） |
| Google Chrome | 推荐本机已安装；用于降低自动化识别概率 |

关闭境外代理后再访问招聘站点，否则页面可能加载异常。

## 快速启动

在项目根目录：

```bash
mvn spring-boot:run
```

或一键脚本（缺库时建库、必要时装前端依赖）：

```bash
./scripts/start.sh          # macOS / Linux
.\scripts\start.ps1         # Windows
```

启动后会：

1. 拉起后端 API（默认 `http://127.0.0.1:8888`）
2. 尽量自动启动前端或使用内置静态资源（管理页 `http://127.0.0.1:6866`）
3. 用 Playwright 打开管理页所在 Chrome 窗口

手动启动前端（开发调试）：

```bash
cd front
npm install
npm run dev
```

仅打包前端到后端静态目录：

```bash
cd front
npm run build:prod
```

## 可分发封装（打开即管理页）

把前端口、后端、SQLite schema 打进一个目录，目标机器**只需 JDK 21+ 和 Chrome**（无需 Maven / Node）：

```bash
./scripts/package.sh        # macOS / Linux → release/getjobs/ 与 release/getjobs.zip
.\scripts\package.ps1       # Windows
```

将 `release/getjobs` 拷到本机任意位置后：

- macOS / Linux：`./start.sh`
- Windows：双击 `start.bat`

首次启动会自动创建 `db/getjobs.db`，并打开管理页。配置与 Cookie 都留在该目录，备份即拷贝整个文件夹。

> 本工具依赖本机 Chrome 与招聘站点交互，**不适合** Docker / 云服务器部署。
## 使用流程

1. 打开管理页 → **环境配置** / **AI 配置**（如需 AI 招呼语与机器人通知）
2. 进入某一平台页（如 Boss）→ 在自动化浏览器的招聘站标签页完成扫码登录
3. 填写搜索关键词、城市与过滤条件 → **保存**
4. 点击 **开始投递**，在管理页查看进度；需要时到「分析」页查看结果
5. 可维护 **全局黑名单**，避免向指定公司投递

同一时刻只保持一个招聘平台标签页活跃；切换平台时会关闭其它平台页。

## 端口与数据

| 项 | 默认值 |
|----|--------|
| 后端 API | `8888` |
| 管理页 | `6866` |
| SQLite | `./db/getjobs.db` |
| 日志 | `./logs/jobs_search.log` |

配置主要保存在 SQLite；AI 的 `BASE_URL` / `API_KEY` / `MODEL`、以及 `HOOK_URL` 可在管理页「环境配置」中维护。

## 注意事项

- **本机运行**：不要部署到云服务器。
- **Boss 风控**：投递过程中可能跳转到 `/web/passport/zp/security.html`（站点安全校验，URL 中的 `ts`/`seed` 非本系统拼接）。若反复校验失败，请重新登录、降低频率后再试。
- **登录态**：以扫码登录为准；Cookie 写入库后下次可复用，过期后需重新扫码。
- **合规**：请遵守各招聘平台用户协议与当地法律；本工具仅供个人求职效率辅助。

## 技术栈

- 后端：Spring Boot 3.5、Playwright、MyBatis-Flex、SQLite
- 前端：Vue 3、Vite、TypeScript、Tailwind CSS

## 目录结构（简）

```
jobs_search/
├── front/                 # Vue 管理端
├── src/main/java/         # Spring Boot + 各平台 Worker
├── src/main/resources/    # 配置、schema、打包后的前端 dist
├── db/                    # SQLite 数据文件（运行时）
├── scripts/
│   ├── start.*            # 开发态一键启动
│   ├── package.*          # 打可分发包
│   └── release/           # 分发包内启动脚本模板
├── release/getjobs/       # package 产物（git 忽略）
└── pom.xml
```
