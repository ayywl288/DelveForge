# DelveForge Frontend

DelveForge 的 Web UI。技术栈：Vue 3 + TypeScript + Vite。

Frontend 只负责用户交互与展示。领域规则、Repository 操作以及 Git / Filesystem / Shell
等本地能力全部在后端，前端不得实现（见 `AGENTS.md` 与 `docs/ARCHITECTURE.md`）。

## 环境要求

- Node.js `^20.19.0 || >=22.12.0`
- npm（随 Node 自带；使用 `package-lock.json`，请勿改用其他包管理器）

## 安装

```bash
npm install
```

## 开发

先启动后端（见仓库根 `README.md`），再启动前端开发服务器：

```bash
npm run dev
```

默认地址 `http://localhost:5173`。

开发服务器会把 `/api` 代理到本地后端，代理目标由 `.env.development` 的
`BACKEND_DEV_URL` 决定，默认 `http://localhost:8080`。

该变量刻意不加 `VITE_` 前缀：Vite 只把 `VITE_` 前缀的变量注入浏览器端产物，
因此后端地址不会进入前端代码。前端始终使用相对路径 `/api/...`，
后端也无需为开发环境开放 CORS。

## 构建

```bash
npm run build
```

该命令先执行 `vue-tsc -b` 做类型检查，再由 Vite 产出 `dist/`。
类型检查失败会导致构建失败，因此不需要单独的类型检查脚本。

本地预览构建产物：

```bash
npm run preview
```

## 目录结构

```text
frontend/
├── index.html
├── vite.config.ts        开发服务器与 /api 代理配置
├── .env.development      本地开发的后端代理目标
└── src/
    ├── main.ts
    ├── App.vue
    ├── style.css
    └── api/              后端 HTTP 调用
```

当前只建立业务 UI 所需的最小工程基础，尚未引入路由、状态管理、UI 组件库与国际化。
