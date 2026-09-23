# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

随心说 / Sign：手语识别结果的语句整理与翻译项目。仓库包含两个互相独立的 Python 子项目和仅供本地使用的数据目录；Android App 不在本仓库，尚未接入。

- `agent/` — FastAPI 云服务（生产已部署）：接收 FINAL 冻结中文原文，调用真实 LLM 整理并按需翻译。唯一接口 `POST /v1/polish`（旧接口 `/api/v1/compose` 已删除，返回 404）。
- `cv/` — MediaPipe 双手关键点提取 + 小型 TCN 分类基线（原型，未训练手语语句权重）。
- `datasets/` — 第三方词典数据库、诊所视频样本、图解画廊。**整个目录被 Git 忽略，仅存本机，无再分发权**；词典子目录有自己的 `datasets/chinese-sign-language-dictionary/CLAUDE.md`，处理该数据前先读它。

根 README 引用的 `docs/`（调研文档）和 `datasets/README.md` 已从仓库移除，链接可能失效。

## 两套独立环境（不要混用）

| 子项目 | Python | venv | 依赖锁 |
|---|---|---|---|
| agent | 3.11+（开发用 3.13） | `agent/.venv` | `requirements-lock.txt`（含 pytest）；生产用 `requirements-prod.lock`（不含 pytest） |
| cv | 3.12 | `cv/.venv` | `cv/requirements-lock.txt`（mediapipe、torch、opencv） |

均用 uv 管理：`uv venv --python 3.13 && uv pip install --python .venv/bin/python -r requirements-lock.txt`。

## 常用命令

```bash
# Agent：启动（自动加载 agent/.env；系统环境变量优先于 .env）
cd agent && .venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload

# Agent：测试（必须在 agent/ 目录下运行，tests 直接 import app.*）
cd agent && .venv/bin/python -m pytest -q
.venv/bin/python -m pytest tests/test_api.py::test_authentication -q   # 单个测试

# CV：关键点提取（NPZ + 覆盖率 JSON + 骨架叠加视频）
cv/.venv/bin/python cv/extract.py video.mp4 --output out.npz --overlay out_overlay.mp4

# CV：训练 / 预测 / 环境自检
cv/.venv/bin/python cv/classifier.py train cv/data/manifest.csv --output cv/outputs/training
cv/.venv/bin/python cv/classifier.py predict sample.npz --checkpoint cv/outputs/training/classifier.pt
cv/.venv/bin/python cv/check_setup.py   # 须在普通本地终端运行，受限沙箱无法初始化图形上下文
```

Agent 配置（`agent/.env`，权限 600，不提交）：`LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` / `LLM_TIMEOUT_SECONDS` / `SERVICE_API_KEY`。**SERVICE_API_KEY 是客户端访问本服务的 Bearer 令牌，与 LLM_API_KEY 是两回事**；真实密钥不入库，向维护者获取。

## Agent 架构（agent/app/，共 4 个模块）

请求流：`main.py`（HTTP 层）→ `service.py`（LLM 调用 + 保真守卫）→ `schemas.py`（契约）。

- `main.py`：`create_app(settings, transport)` 工厂接受注入的 `Settings` 和 httpx transport，这是测试 seam；模块级 `app = create_app()` 供 uvicorn 使用。Bearer 鉴权用 `secrets.compare_digest`；三类 exception handler 把所有异常统一映射为错误契约 JSON。
- `schemas.py`：Pydantic 契约，字段 camelCase、`extra='forbid'`、strict 类型（revision 拒绝 bool/字符串/浮点）。语言标签是自定义正则，非完整 BCP 47。
- `service.py`：`polish()` 调用 OpenAI 兼容协议，程序在 `LLM_BASE_URL` 后追加 `/chat/completions`。上游状态码映射：401/403→502 `MODEL_AUTH_FAILED`，429→503 `MODEL_RATE_LIMITED`，超时→504 `MODEL_TIMEOUT`，其余非 2xx→502 `MODEL_UPSTREAM_ERROR`（≥500 可重试）。
- `config.py`：`Settings.from_env()` 合并 `.env` 与系统环境（后者优先）；`LLM_BASE_URL` 必须是无凭据/查询/片段的 HTTP(S) 前缀。

### 必须保持的不变量（有测试盯着）

1. **生产代码零模拟逻辑**：`app/` 内不得出现 mock、固定示例输出、模拟模式；测试替身（`httpx.MockTransport`）只允许在 `tests/`。OpenAPI schema 不含 example。
2. **错误契约**：所有错误返回 `{segmentId?, revision?, error: {code, message, retryable}}`，用户可见 message 为中文；**绝不泄漏上游供应商的响应细节**（测试断言 upstream 文本不出现在响应中）。
3. **10 秒硬上限**：实际超时 = `min(LLM_TIMEOUT_SECONDS, X-Remaining-Budget-Ms/1000, 10)`，即使环境变量配 30 秒也被钳到 10。不要通过放宽期限掩盖模型延迟问题。
4. **`guard_result()` 是启发式保真检查**，不证明跨语言语义正确：中文侧比对数字（阿拉伯/汉字）、否定词计数、分隔重复词；外语侧检测"中文原样复制冒充译文"和数字漂移。可疑时追加 `FIDELITY_CHECK_FAILED` issue 而非改写文本；`UNAVAILABLE` 的语言文本必须从响应中移除（中文置 null，外语从 translations 删除）。修改守卫逻辑时保持"保守提示、不静默修正"的原则。
5. **服务无状态**：不保存会话、不做去重；代次、队列、截止时间、TTS 都是手机端 LanguageProcessor 的职责（见 `docs/API_new.md` §6 的系统分工）。

契约变更需同步 `agent/docs/API.md`、`docs/openapi.json`、`docs/接口调用说明.md`（面向 App/Apifox 的中文调用说明）。

## 部署（agent/deploy/ + docs/DEPLOYMENT.md）

生产链路：公网 HTTPS :443 → Nginx（IP 限流 30 req/min、body ≤128 KiB）→ 127.0.0.1:8000 uvicorn → 模型供应商。服务器 `ecs-user@101.37.234.129`，代码在 `/opt/insta360-agent`，systemd 单元 `insta360-agent.service`。IP 证书为 Let's Encrypt 短期证书，`insta360-cert-renew.timer` 每天两次自动续期——不要关闭 80 端口或续期任务。更新部署只同步 `app/` 和锁文件，保留服务器上的 `.env` 与 `.venv`；依赖变更后用 `uv pip sync requirements-prod.lock` 并重启服务。

## CV 架构（cv/）

- `extract.py`：MediaPipe HandLandmarker（float16/1，`models/hand_landmarker.task`，不提交 Git），VIDEO 模式双手。左右手按 handedness 分槽，同槽冲突保留高分者；长边缩至 ≤1280 推理，不裁剪。输出 NPZ：`landmarks (F,2,21,3)` + `present` 掩码 + 时间戳（按文件帧率推算）+ fps，另附覆盖率 JSON。**坐标是图像归一化坐标，z 是手腕相对估计深度——两只手的 z 不在同一坐标系，不能当真实深度用**；交叉遮挡可能导致左右手误判，必须看叠加视频核实。
- `classifier.py`：特征 = 手腕相对的局部手形（按中指根距离归一化）+ 原始手腕 xy + 手存在标记，共 132 维，最近邻采样到 64 帧（**故意不插值**，避免把缺失手插成假位置）。两层 Conv1d TCN，MPS 优先、CPU 回退；按验证集 loss 存最佳 checkpoint（含 `feature_version`，加载时校验）；test 只评估一次。预测输出 top-3 未校准 softmax + `needs_confirmation: true`——不是可靠概率，不能自动播报。
- 训练清单 `manifest.csv` 列：`video,label,signer,session,split`。脚本强制防泄漏：同一 (signer, session) 不得跨 split、视频路径不得重复、所有类别必须出现在三个 split。改名/重复剪辑造成的泄漏脚本拦不住，需人工保证。全程无手的视频会被拒绝。
- `models/`、`data/`、`outputs/` 均被忽略（保留 `models/.gitkeep`、`data/manifest.example.csv`）。

## 数据（datasets/，全部仅存本机）

- `chinese-sign-language-dictionary/`：《国家通用手语词典》衍生的 SQLite 库（signs.db / sign_themed.db）+ 6699 张手势图。**仅限非商业用途**；EPUB 源书不入库。schema、解析管线和不变量见其目录内 CLAUDE.md。
- `csl-clinic-sample/`：诊所场景手语视频样本 + CSV（列：`file_name,gloss,text,source_split`）。
- `demo_help_vocab/`：求助场景词汇的 manifest 与生成画廊 HTML。

数据整理脚本需先按各目录 README 准备本地材料，克隆仓库不能重建完整数据。
