# GO 3S 手语翻译 — 执行计划

> **状态：草案 v1** | 2026-09-22
> 行为规范见 `ARCHITECTURE.md`（冲突时以其为准），接口契约见 `API.md`。本文只回答"按什么顺序做、每步怎么算完成"。

---

## 0. 现状盘点（2026-09-22）

### 已完成

| 项 | 状态 |
|---|---|
| 架构设计 | ✅ `ARCHITECTURE.md`（修订稿，数值待实测回填） |
| 接口契约 | ✅ `API.md`（模块接口 + USB 线协议 v1） |
| 开发环境 | ✅ Android Studio Quail 4 Patch 1、SDK platform-tools/adb 可用 |
| 第 0 周门禁·连接 | ✅ 实测通过（BLE 扫描→Wi-Fi 按钮→系统热点确认→连接成功） |

### 已实测的相机/流事实

| 事实 | 来源 |
|---|---|
| 相机已激活（无需再走激活） | GetOptions `activate_time` 有值 |
| 取流默认：H.264、30fps、主流 640×360 + 副流 640×480、约 4Mbps、无音频、带 gyro | `startStream` 日志 |
| 实际编码尺寸 640×384（5:3 鱼眼），声明值 ≠ 实际值 | 解码器 ImageData 日志 |
| CSD 61 字节随流发送；首帧开流后约 2s 出图 | 同上 |
| `startStream()` 无参，分辨率 SDK 内部定；`startLive(CameraLiveParams)` 可指定分辨率但走 RTMP 路径 | SDK dex 接口面 |
| **高分辨率取流可行**：`VIDEO_LIVE` 模式主流跟随 `video_resolution`，实测 3840×1920@30 开流成功；1080p 只需设 `video_resolution` + live 模式开流，无需真推 RTMP | P0 推流实测 |
| Demo"预览分辨率 1080P"选项只改渲染尺寸，不改相机流 | 实测（无流重启） |
| 错误码 -214 = 约 5s 连接超时（典型原因：手机不在相机网络） | 实测 |
| 电池温度开流期间 53°C 且上升 | GetOptions |
| Demo 日志明文打印热点 SSID/密码（我们 App 必须避免） | 实测 |
| MIUI 会冻结后台 Demo 进程（Greeze） | logcat |

### 未验证/未完成

- ~~1080p（及以上）取流是否可行~~ ✅ 已验证可行（`VIDEO_LIVE` + `video_resolution`，实测 4K 开流成功）
- ~~持续取流稳定性、断连重连表现~~ ✅ P2/P3 真机矩阵完成；温度观察按用户决定移除（2026-09-23，归因见 P3 真机发现 5）
- ~~自研工程一行代码未写~~ ✅ P1–P3 已落地（骨架/连接/取流+解码，单测 105 条 0 红）

---

## 1. 阶段划分

### P0 门禁收尾——**当前所处阶段**

| 任务 | 完成标准 |
|---|---|
| ~~1080p 取流验证~~ | ✅ 已完成：`VIDEO_LIVE` 模式实测 4K（3840×1920@30）开流成功，分辨率上限风险解除（注意：测试推流用的 YouTube stream key 已入日志，待重置） |
| 30 分钟持续取流观察 | 记录掉帧/掉流次数、温度曲线、是否触发过热保护 |
| 手动断连重连 3 次（关相机/走远） | 记录 Demo 的恢复表现（这决定我们重连设计的兜底强度） |
| ~~结论回写文档~~ | ✅ 已回写 `ARCHITECTURE.md` §8（P0 实测记录表） |

**产出**：分辨率结论（直接决定 ModelSpec 输入预算与 USB 吞吐设计）。

### P1 工程骨架

| 任务 | 完成标准 |
|---|---|
| ~~Gradle 工程：锁定 AGP 8.7.3 / Gradle 8.11.1 / JDK 17 / Kotlin 2.3.20 / minSdk 29 / compileSdk 35 / arm64-v8a~~ | ✅ 两 flavor debug + productionRelease 均构建通过（2026-09-22） |
| ~~Maven 凭据本地注入（不进仓库）~~ | ✅ 凭据走 `~/.gradle/gradle.properties`，仓库零凭据 |
| ~~空壳：Application、前台服务声明、权限清单按 Demo 裁剪~~ | ✅ Manifest 合并通过（allowBackup 冲突已用 tools:replace 解决）；单元测试 p1 全绿 |
| 装机验证 | ⏳ 用户决定推迟：P2 首次装机时顺带补验（2026-09-22） |

### P2 相机连接模块

| 任务 | 完成标准 |
|---|---|
| `CameraSession` 状态机 + BLE→AP→热点→绑网→connect 全流程（API.md §1） | 真机连接成功率 ≥ 连续 10 次成功 |
| 断线重连（1/2/4/8/16s × 5）+ 通知/震动 | 拔电池/超距场景按矩阵恢复或明确失败 |
| 权限拒绝路径、停止清理顺序（§2.1.3） | 杀进程/锁屏/切后台各场景不泄漏、不重叠会话 |

**进度（2026-09-23，真机矩阵完成）**：状态机/退避策略/连接链路（`SdkCameraSession`，
照 Demo ConnectionViewModel 抄录）+ SessionEvent 广播 + FGS 持有会话 + 验证 UI 已落地，
单测 82 条（37 绿 / 45 分阶段 @Ignore / 0 红）。真机验收：连续 10 次连接 10/10、
短暂断电退避自愈（1/2/4/8/16s 第 5 次恢复）、5 次退避耗尽→Error+ReconnectFailed、
Error→用户重连恢复。真机驱动修正 4 处：初连瞬时错误自动重试（相机休眠唤醒失败/
快速重连 GATT 133）、SDK BLE connect 挂死加 60s 尝试级 watchdog（实测挂死 108s）、
系统热点连接 10s 超时（系统对不可用网络可拖 37s）、重试预算进 Checking 即重置
（不跨会话残留）。

**真机发现（P3 输入与修正项）**：
1. **相机在"已连接但无取流"空闲态约 1 分钟自动休眠**（WiFi 心跳超时断连）——
   P3 必须在连接完成后立即开流，否则连接无法维持；30 分钟持续取流观察随 P3 做。
2. SDK 默认日志会把热点密码明文打进 logcat——**production 构建必须调高
   InstaCameraConfig.logLevel**。
3. MIUI 杀进程问题按用户决定不纳入范围（2026-09-23）。

**已知风险**：MIUI 冻结后台进程（Greeze）——按用户决定不关注。

### P3 取流 + 解码——**App 侧最高技术风险**

| 任务 | 完成标准 |
|---|---|
| ~~方式二：`onStreamDataNotify` → 同 timestamp 分片聚合 → timestamp 切换提交~~ | ✅ 满帧率解码 + 满帧率帧计数即帧边界正确的证据（边界错则硬解必然花屏/丢帧） |
| ~~MediaCodec 无 Surface 解码 + `getOutputImage` 布局读取/复制~~ | ✅ 30fps 满帧率、c2.qti.avc.decoder、实际 640×384 |
| ~~参数集/关键帧准备、重同步、`streamGeneration`~~ | ✅ 断电重连 gen2 干净重门控恢复，旧代次零串扰 |
| ~~有界队列 + 过载处理（§2.2.5 初始值）~~ | ✅ 单测注入慢消费覆盖（过载单次报告/丢弃/恢复）；真机自然负载未触发过载 |
| ~~时间戳体系（µs 媒体时间 / 单调时钟分离）~~ | ✅ 单元测试覆盖 |

**进度（2026-09-23，取流+解码链真机验证通过）**：`FrameAssembler`（8 条契约单测）
+ `ChunkIngestQueue`（过载单次报告/丢弃/恢复 6 条单测）+ `SdkCameraSession` 开流接线
（连接后立即开流 → 入口队列 → 聚合 → `getPreviewParams` 查询进 Streaming）。
解码链：`H264DecodePrep`（Annex-B NAL 扫描、SPS/PPS→CSD、参数集齐全+IDR 才标记
isSyncPoint、纯参数集/SEI 帧吸收、代次重置，7 条单测）→ `DecodeFrameQueue`（§2.2.5
表 3 有界队列，6 条单测）→ `DecodeSyncGate`（每代次从首个随机访问帧起投喂，
5 条单测）→ `SurfacelessH264Decoder`（无 Surface configure + `getOutputImage`
平面复制）→ `DecodedFrame/FramePlane/PixelLayout` 契约类型（API.md §2.1）。
单测合计 105 条 0 红、38 条分阶段 @Ignore。

真机实测（小米 2510DRK44C，c2.qti.avc.decoder 硬解）：
- **满帧率解码**：解码 30.0 fps 与取流一致；实际解码尺寸 **640×384**（声明
  1920×1080 只是 preview 渲染尺寸，SPS 实际为 5:3 鱼眼 640×384）
- **断电重连不花屏**：Disconnected 事件触发 → 退避重连成功 → gen2 干净重门控
  （起步损失 ~9 帧/300ms），恢复后 30 fps 满帧率；旧代次数据零串扰
- **零过载、零解码错误**；解码起步 ~9 帧为 IDR 前不可解码帧 + decoder 冷启动，
  属压缩域本质约束（P6 以 `onGap`/sequenceEpoch 消化），不做额外优化
- 时间戳体系：ptsUs（媒体时间）与 mono 时钟（排队时长/账目）分离，队列单测覆盖

真机驱动修正：**GO 3S 不触发 `onParamsChanged`**（全程零回调），
改为 `onOpened` 后轮询 `getPreviewParams()`（返回 `Result<PreviewParams>`，
Result 为 value class 导致方法名混淆）。

**真机发现（P3 输入）**：
1. P0 推流残留的 `video_resolution=4K` 会让相机持续做无用 4K 编码，电池温度冲到
   57°C 后相机侧挂死（冻屏、ERR_SOCKET_READ 强制断连）——已用 Demo App 复位回
   1080p；后续 App 内如需改分辨率须走 setOptions 并记录。
2. **GOP 超长（>1000 帧）**：4.5 分钟会话仅 2 个自然 IDR；随机访问完全依赖开流
   首帧（CSD+IDR 随流）与 `requestStreamIframe()`——重同步必须主动请求关键帧，
   不能干等下一个 IDR。
3. 4K 档实测收到的 VIDEO 流仍是低分辨率流（~9KB/帧与 4Mbps 档吻合）——已核实：
   VIDEO_NORMAL 模式下声明 preview=1920×1080 为渲染尺寸，实际编码主流即 640×384。
4. SDK 噪声：BLE 释放后 CommandExeManager 残留重试，每 ~2s 刷一条 FastBle
   write error 日志，不影响 Wi-Fi 取流（后续若需静默须在 App 侧规避）。
5. 30 分钟持续取流观察与温度曲线按用户决定移除（2026-09-23）：过热风险已归因于
   4K 编码残留并解除；当前低负载主流（~4Mbps）下会话稳定性以零断连/零过载验收。

### P4 训练采集通道（可与 P5 并行）

| 任务 | 完成标准 |
|---|---|
| ~~`UsbBridgeService` + 线协议 v1（API.md §9）~~ | ✅ 真机实测：60 秒 1799 帧满帧率（30.0 fps）、10.5 MiB/s（理论 11.1）、零 GAP 零缺帧；**预算档位定案 128 MiB**（2026-09-23） |
| ~~PC Python 客户端（uv 管理）~~ | ✅ 真机联调通过：AUTH/心跳/收帧落盘/完整性校验，END COMPLETE（792 帧）与 --duration 主动停路径实测；模拟手机侧 7 用例联调（2026-09-23） |
| 采集授权流程与素材元数据 | §2.8.3 授权记录可追溯 |

**进度（2026-09-23，真机吞吐+联调通过）**：协议栈 `FrameCodec`（分帧/两段截止）
→ `BridgeSession`（握手/心跳/超时状态机）→ `FrameSendPool`（§9.4 背压账目）
→ `I420.compact`（设备相关布局归一）→ `UsbBridgeServer`（accept/reader/pump 三
线程，单写者串行写出，新连接重置采集段）→ `DecodedFrameSink`（§2.2 契约，
解码线程回调）→ `UsbCaptureService`（training FGS，配对令牌仅显示于通知栏）
→ `CaptureEntry` flavor 缝（production 编译期无采集代码，`compileProductionDebugKotlin`
通过）。PC 侧 `pc/csl_capture.py`（uv，纯标准库）：AUTH → SESSION_CONFIG 校验
（仅 I420）→ select 主循环（2s 心跳/6s 静默断开）→ 逐帧校验 payloadLen×尺寸
落盘（frames.i420 + meta.jsonl 索引 + summary.json）→ END 时核对序号连续性，
COMPLETE 但有缺口按协议违规上报。单测 145 条 0 红、38 条分阶段 @Ignore。

真机实测（小米 2510DRK44C ← GO 3S 640×384@30 H264 → adb forward）：
- **吞吐**：60 秒 1799 帧（632.5 MiB）、30.0 fps 满帧率、10.5 MiB/s 持续
  （640×384 I420 理论 11.1 MiB/s）；128 MiB 池 ≈ 12 秒积压 ≫ 2 秒缓冲目标，
  **128 MiB 档定案**（256/64 档仅在规格变更时启用）
- **素材完整性**：序号 0..1798 连续、offset/len 与 640×384×1.5 一致、pts 单调
  （中位间隔 33000µs）；首帧 I420→RGB 抽查为真实画面
- **END COMPLETE 路径**：手机 UI「停止采集服务」→ END COMPLETE，PC 退出码 0
  （792 帧段）；`--duration` 主动停 → CLIENT_STOP（退出码 0，素材保留）
- 联调修掉一个 spec 偏差：`AUTH_RESULT` 缺 `proto` 公共字段（§9.2）——PC 严格
  校验揭穿，手机端补齐并加单测断言
- 采集素材目录示例：`<out>/<时间戳>_<sessionId>/{frames.i420, meta.jsonl,
  summary.json}`（段间不拼接，GAP 后素材标不完整）

### P5 训练与模型（外部依赖，**最早启动、最晚交付**）

不阻塞 P1–P4，但决定最终发布能力：

- 数据采集 → 训练 → 连续识别 + 自动边界 → `ModelSpec` 定稿
- **发布门槛**：自动边界未经验证 → 不发布连续翻译（不加逐句按钮兜底，见 ARCHITECTURE.md §9）
- PC/Android 预处理一致性验证（张量容差比对）

### P6 识别与句子管理（依赖 ModelSpec 草案）

- `SignRecognizer` 适配器、`SentenceManager` 状态机（API.md §4/§5）
- 验收：重叠窗口、边界信号、中断/过期回调全路径测试

### P7 语言处理 / TTS / 缓存 / UI

- `LanguageProcessor`（本地优先，云端留接口）、`TtsManager`、Room 缓存、字幕 UI
- 验收：否定/数字保真样本集、TTS 去重键、90 天/万条清理、导出

### P8 验收（持续，按 ARCHITECTURE.md §8 全表）

每阶段结束跑对应行；发布前全表过一遍 + 持续 30 分钟压力 + 训练隔离检查（productionRelease 无采集入口/端口/落盘）。

---

## 2. 依赖关系与并行

```text
P0 ──► P1 ──► P2 ──► P3 ──► P4 ──┐
                 │               ├──► P6 ──► P7 ──► P8 发布
                 └──► P5（模型训练，外部周期）──┘
```

- P0 的分辨率结论 → P3 解码规格、P4 吞吐预算、P5 ModelSpec 输入
- P5 与 P1–P4 完全并行；P5 的 ModelSpec 草案尽早冻结输入格式（分辨率/采样率），避免 P6 返工
- P7 的 LLM 本地引擎选型（MediaPipe LLM / llama.cpp）可在 P3 期间用样机预研

---

## 3. 待用户决策项

| # | 决策 | 影响阶段 | 备注 |
|---|---|---|---|
| ~~1~~ | ~~640×360 若确认不够、1080p 也不可行时的降级策略~~ | — | ✅ 已解除：live 模式可取 1080p/4K，改为"取流分辨率档位选型"（在 1080p 与 4K 间按发热/带宽/模型需求定，P3 实测后定） |
| 2 | 项目绝对截止日期 | 全局 | 确定后回填，决定 P6/P7 裁剪范围 |
| 3 | LLM 本地引擎（MediaPipe / llama.cpp / 仅云端） | P7 | 目标手机实测后定 |
| 4 | 云端 LLM 是否本期实现 | P7 | 可只留 `LlmPolisher` 接口 |
| 5 | 目标手机范围（当前实测机：小米 2510DRK44C / MIUI） | P2/P8 | OEM 差异决定测试矩阵 |

## 4. 风险清单（按优先级）

| 风险 | 等级 | 缓解 |
|---|---|---|
| ~~相机 liveview 分辨率上限不足~~ | ~~高~~ | ✅ P0 已解除：live 模式实测 4K 开流成功；遗留发热问题（见下） |
| 自动边界模型能力不达标 | 高 | P5 尽早启动；不达标则收缩产品口径为"独立动作识别" |
| 相机过热（实测 53°C 起步） | 低 | ✅ 已归因解除：过热由 4K 编码残留引起（P3 真机发现 1），低负载主流未复现；温度观察按用户决定移除 |
| MIUI/国产 OEM 杀前台服务 | 中 | P2 就验证 FGS 存活；明确支持机型范围 |
| ~~SDK 分片聚合契约与实际不符~~ | ~~中~~ | ✅ P3 已解除：满帧率解码验证帧边界正确 |
| USB 吞吐不足 | 低 | P4 实测带宽后定预算档（128/256/64 MiB 或降规格） |

---

## 5. 立即的下一步

1. **P4 收尾**：采集授权流程与素材元数据（§2.8.3 授权记录可追溯）——通道与
   PC 客户端已真机联调通过（2026-09-23），采集可先行试采
2. P5 数据采集与训练尽早启动（外部周期最长）；P6 识别与句子管理可依赖
   ModelSpec 草案并行起步
