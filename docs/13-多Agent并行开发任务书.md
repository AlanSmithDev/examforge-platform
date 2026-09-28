# 多 Agent 并行开发任务书（今晚 → 明早 06:00 代码完成+测试通过，07:00 部署）

> 版本：v1.0 ｜ 总目标：**明早 6:00 前 MVP 代码全部完成且冒烟测试全绿；7:00 前完成部署**。
> 范围：可运行的全栈 MVP（后台 API + 超管后台 + 前台核心页面接入 API + 广告/Logo 占位体系 + 测试 + 部署脚本）。
> 设计原则：UI 明早 6 点由用户亲自调整，因此**所有视觉元素集中在 design tokens 与占位组件**，改 UI 不用动代码逻辑。

## 0. 技术栈与部署形态（已定，不再讨论）
- 后端：**Node.js 18 + Express + better-sqlite3 + JWT（jsonwebtoken）+ bcryptjs**。零外部服务依赖，`npm install && node server/server.js` 即可跑，天然适合 7 点前部署（本机/任意云主机/Student 云服务器）。
- 数据库：SQLite 单文件 `server/data/examforge.db`（首次启动自动建表+种子数据）。后续迁 MySQL 的 DDL 已在 docs/04。
- 前台：复用 `prototype/` 设计系统的静态页，数据经 `fetch('/api/v1/...')` 注入。
- 超管后台：`admin/` 独立单页应用（同一设计系统）。
- 部署：`deploy/` 内含 Dockerfile 与 Windows/Linux 双平台一键脚本；健康检查 `/api/v1/health`。

## 1. 目录结构（文件所有权边界——多 agent 严禁越界改文件）
```
zujuan-platform/code/app/
├── package.json            # WP-0 所有
├── server/
│   ├── server.js           # WP-0 所有（路由挂载，禁止改业务逻辑）
│   ├── db.js               # WP-0 所有（建表+种子数据）
│   ├── auth.js             # WP-1 所有（JWT 中间件+角色）
│   ├── routes/questions.js # WP-2 所有
│   ├── routes/basket.js    # WP-3 所有
│   ├── routes/papers.js    # WP-3 所有
│   ├── routes/ads.js       # WP-4 所有（前台广告位读取）
│   ├── routes/admin.js     # WP-4 所有（超管全部写接口）
│   └── data/               # 运行时生成（.gitignore）
├── admin/index.html        # WP-6 所有（超管后台单页）
├── tests/smoke.mjs         # WP-8 所有（冒烟测试）
├── deploy/                 # WP-8 所有（Dockerfile + 部署指南）
└── .env.example
prototype/                  # 前台页面
├── assets/style.css        # 冻结：UI 设计师专用，agent 不得改已有类
├── assets/placeholders.css # WP-5 所有（广告/Logo/图片占位组件）
├── index.html              # WP-5/WP-7 可改（只允许按占位规范插槽+fetch注入）
└── xuanti.html / timu.html # WP-7 可改（同上）
```

## 2. 接口契约（实现必须与 docs/05 一致；统一响应 `{code:0,message,data}`）

| # | 接口 | 方法 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | /api/v1/health | GET | 公开 | `{status:"ok",db:true}` |
| 2 | /api/v1/auth/register, /login | POST | 公开 | 手机号+密码；返回 accessToken；教师/学生角色 |
| 3 | /api/v1/auth/admin-login | POST | 公开 | 超管/运营/编辑登录（独立入口） |
| 4 | /api/v1/stages,/subjects,/catalog | GET | 公开 | 学段/学科/章节树 |
| 5 | /api/v1/questions | GET | 公开(限流) | 分页+筛选（subjectId/scene/type/difficulty/category/kp/keyword） |
| 6 | /api/v1/questions/:id | GET | 公开 | 详情：五段式解析+元数据+相似题 |
| 7 | /api/v1/basket | GET/POST/DELETE | 登录 | 试题篮（上限100） |
| 8 | /api/v1/papers/generate | POST | 登录 | 规则引擎智能组卷（按蓝图：题型数量+难度分布+去重） |
| 9 | /api/v1/ads?position=x | GET | 公开 | 上线广告列表；**空返回时前台渲染占位框** |
| 10 | /api/v1/settings | GET | 公开 | 站名/Logo URL/备案号（前台占位自动替换） |
| 11 | /api/v1/admin/ads CRUD | POST/PUT/DELETE | SUPER_ADMIN/OP | 广告位管理（含素材URL、排期、端别） |
| 12 | /api/v1/admin/questions CRUD+上下架 | POST/PUT | SUPER_ADMIN/EDITOR | 题目管理（软删留审计） |
| 13 | /api/v1/admin/users | GET/PUT | SUPER_ADMIN | 封禁/认证审核/赠送会员 |
| 14 | /api/v1/admin/notices CRUD | * | SUPER_ADMIN/OP | 公告推送 |
| 15 | /api/v1/admin/settings PUT | PUT | SUPER_ADMIN | 系统设置（Logo/站名/备案号） |
| 16 | /api/v1/admin/audit | GET | SUPER_ADMIN | 操作审计日志（只读） |

**SSRF 验收条件（写入所有涉及外呼的实现）**：服务端抓取 URL 仅允许 http/https；请求前解析 host 并拒绝 localhost/环回/私有(10.x,172.16-31.x,192.168.x)/保留地址；不跟随跨协议重定向。

## 3. 工作包（WP）与并行分工

| 包 | 内容 | 验收标准（测试命令） | 建议执行者 |
|---|---|---|---|
| WP-0 脚手架 | package.json/db.js/server.js/种子数据（6 学段、高中数学 30 题 LaTeX、广告位空表、超管账号 admin/Admin@123456、设置表） | `node server/server.js` 启动无错；/health 返回 db:true | **主 agent（今晚立即）** |
| WP-1 认证 | auth.js：JWT 签发/校验/角色中间件 | 冒烟测试 §4 T1 | 主 agent |
| WP-2 题库 API | questions 列表筛选/详情/相似题（同知识点随机 3 题） | T2 | 主 agent |
| WP-3 组卷 | basket + 规则引擎组卷（题型数量/难度分布/排除已选/难度曲线排序） | T3 | 主 agent |
| WP-4 超管 API | ads/questions/users/notices/settings/audit 全套写接口+SSRF 校验+审计落库 | T4 | 主 agent（或第二会话） |
| WP-5 占位体系 | placeholders.css + index/xuanti 页面插 Logo 槽/广告槽/图片槽（空位显示占位框，带槽位码与尺寸建议） | 打开页面可见占位框；配置广告后自动替换 | 主 agent（或第二会话） |
| WP-6 超管后台页 | admin/index.html：登录/数据看板/广告位/题目/用户/公告/系统设置/审计 八个模块，调用 WP-4 接口 | 手工路径：登录→建广告→前台占位变素材 | **第二会话 agent 可领** |
| WP-7 前台接入 | xuanti/timu 页面 fetch 真实数据渲染（保留静态兜底） | 断网时页面仍可看（静态兜底），联网显示 DB 数据 | **第三会话 agent 可领** |
| WP-8 测试部署 | tests/smoke.mjs（T1-T5）+ Dockerfile + deploy/部署指南.md + 启动脚本 | `node tests/smoke.mjs` 全绿；docker build 成功 | **主 agent 最后收口** |

## 4. 冒烟测试清单（tests/smoke.mjs 断言）
- T1 认证：注册→登录→带 token 访问 basket；超管登录返回 role=SUPER_ADMIN。
- T2 题库：列表筛选（subject=高中数学&难度=较难）返回 10 条且含 LaTeX 字段；详情含五段式解析键。
- T3 组卷：建蓝图（8单选+3多选+3填空+5解答）→ generate 返回试卷含 19 题且无重复、难度分布偏差 ≤15%。
- T4 超管：admin-login→创建广告(home_banner)→/api/v1/ads?position=home_banner 返回该条→禁用后返回空数组；未带超管 token 写操作返回 40301；SSRF：广告跳转 URL 填 http://127.0.0.1 被 422 拒绝。
- T5 占位数据：/settings 返回默认 Logo 为空串 → 前台渲染占位。

## 5. 并行执行方案（针对你的套餐并发=1 的现实）
- **方案 A（默认，已执行）**：主 agent 按 WP-0→8 串行完成（一个 agent 顺序做完全部包，6 点前可交付）。
- **方案 B（提速）**：你现在/明早再开 1-2 个 ZCode 会话，把本文档 §3 的 WP-6、WP-7 交给它们（文件所有权隔离，不会冲突），主 agent 让出对应包。
- **方案 C（升级套餐后）**：用动态工作流（CreateWorkflow）max_concurrency=4 一次派 4 个子代理并行跑 WP-2/3/4/6。
- 冲突纪律：所有 agent **只改自己包的文件**；统一响应格式与错误码照 §2；UI 类只允许动 `assets/placeholders.css` 和标注"WP-7 可改"的页面。

## 6. 里程碑时间表（当晚）
| 时间 | 交付 |
|---|---|
| T+0 ~ T+1h | WP-0/1（脚手架+DB+种子+认证）跑通 /health |
| T+1 ~ T+2h | WP-2/3（题库+组卷引擎） |
| T+2 ~ T+3.5h | WP-4（超管 API+SSRF+审计） |
| T+3.5 ~ T+5h | WP-5/6（占位体系+超管后台页面） |
| T+5 ~ T+6h | WP-7/8（前台接入+冒烟测试全绿+部署脚本） |
| 06:00 | 代码冻结，交付用户做 UI 设计 |
| 06:00~07:00 | 部署（deploy/一键脚本）+ 健康检查 + 演示数据重置 |
