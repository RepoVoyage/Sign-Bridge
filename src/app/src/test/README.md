# 单元测试目录 — 阶段映射与 test-first 流程

按 plan.md 阶段分包。约定：

1. **进入某阶段**：去掉该阶段测试类上的 `@Ignore` → 跑 `./gradlew test` 应为**红** → 写实现 → 绿。
   阶段内新行为：先加测试再改代码。
2. 占位测试（`fail("Px 待实现：…")`）是把 plan.md/API.md 验收标准固化在测试目录的清单，
   开工时逐条替换成真断言；断言引用的生产类型在各阶段开工时才创建，不提前编造接口。
3. Gradle 标准 JVM 单元测试位置是 `app/src/test/java/`（自动接线），故未采用顶层 `tests/` 目录。

| 阶段 | 测试文件 | 覆盖 | 状态 |
|---|---|---|---|
| P1 骨架 | `p1/P1ScaffoldTest` | 入口类、FGS 壳（构建/Manifest 合并由 assemble 验证） | ✅ 绿 |
| P2 连接 | `p2/ReconnectPolicyTest`、`p2/SessionStateFlowTest` | 退避序列、状态机全路径 | 🔴 @Ignore，开工时激活 |
| P3 取流解码 | `p3/FrameAggregationTest` | 分片聚合、时间戳、超限重同步、代次隔离 | 🔴 @Ignore |
| P4 USB 桥 | `p4/WireProtocolTest` | 分帧 roundtrip、长度上限、认证/读超时、心跳 | 🔴 @Ignore |
| P5 模型训练 | —（PC 侧） | 训练/验证在 PC Python 项目（`pc-training/`，uv 管理），测试随该项目建立 | 计划 |
| P6 句子管理 | `p6/SentenceManagerTest` | 段状态机、边界信号、迟到结果丢弃 | 🔴 @Ignore |
| P7 TTS/缓存 | `p7/TtsAndCacheTest` | 去重键、队列超时、保留策略、LLM 期限 | 🔴 @Ignore |
| P8 验收 | —（真机/人工） | plan.md P8 + ARCHITECTURE §8 全表；Instrumented 测试按需补充 | 计划 |

跑法：

```bash
cd src
JAVA_HOME=~/jdk17 ./gradlew test            # 全 variant 单测
JAVA_HOME=~/jdk17 ./gradlew testTrainingDebugUnitTest   # 只跑 training debug
```

P0 遗留的 30 分钟持续取流观察、断连重连 3 次为真机人工项（plan.md §1 P0），不在此目录。
