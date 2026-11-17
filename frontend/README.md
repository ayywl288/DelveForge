# DelveForge 工作台前端

`src/App.vue` 挂载项目工作台。项目、对话、Run、工具活动、模型及凭据设置通过同源 `/api/workbench` 获取。浏览器只保存界面选择和待确认输入；提供方密钥在提交后清空。

使用项目声明的 pnpm 版本。从此目录执行 `pnpm install --frozen-lockfile`、`pnpm run typecheck`、`pnpm test` 和 `pnpm run build`。`pnpm dev` 启动 Vite，本机代理目标由 `BACKEND_DEV_URL` 配置。[启动完整工作台](../docs/LOCAL_SETUP.md)
