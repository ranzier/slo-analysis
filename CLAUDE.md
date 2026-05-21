# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 构建与运行

```bash
# 构建（跳过测试）
./gradlew build -x test

# 运行测试
./gradlew test

# 运行单个测试类
./gradlew test --tests "co.bilibili.slo.SomeTestClass"

# 运行主流水线
./gradlew bootRun --args="pipeline <app_path> [--top-days 2] [--top-apis 3] [--days 7] [--skip-ai]"

# 其他命令
./gradlew bootRun --args="day <app_path> --date 2025-05-18"
./gradlew bootRun --args="compare <app_path> --baseline 2025-05-10 --target 2025-05-18"
./gradlew bootRun --args="diff <app_path>"

# 打包并运行
./gradlew build
java -jar build/libs/slo-analysis-1.0.0.jar pipeline open.bangumi.view-gateway
```

- Java 17（toolchain），Gradle 8.12（Kotlin DSL），Spring Boot 3.4.1
- CLI 框架：picocli 4.7.6

## 架构概览

这是一个 CLI 工具，用于分析 SLO（服务等级目标）数据，检测异常并通过 AI 进行根因分析。是 Python 版 SLO 分析工具的 Java 移植版。

### 数据流

```
CLI (picocli) → 流水线编排器 → 爬虫（HTTP 请求）→ 分析引擎 → 输出（JSON/Markdown）
```

### 各层职责

- **cli/** — picocli 命令（`pipeline`、`day`、`compare`、`diff`）。`CliRunner` 负责桥接 Spring Boot 和 picocli。
- **pipeline/** — 编排器，协调多步骤工作流。`PipelineOrchestrator` 执行主流水线的 7 个步骤：拉取 SLO → 异常检测 → 拉取错误数据 → 尖刺检测 → 查询日志 → 聚合 → AI 分析。
- **crawler/** — 响应式 HTTP 客户端（Spring WebClient），从 Watcher（SLO 指标）、Billions（日志）和错误面板拉取数据。
- **analysis/** — 纯计算层。`AnomalyDetector`（MAD 算法）、`ErrorSpikeAnalyzer`（两阶段尖刺检测）、`LogAggregator`（10 维聚合）、`AiAnalysisService`（调用 Claude API）。
- **config/** — `SloProperties` 绑定 YAML 配置，兼容回退到 `../slo-agent/config.json`（Python 版配置）。`WebClientConfig` 创建三个 WebClient Bean（watcher、billions、ai），各自使用不同的认证头。
- **model/** — Java Record，用于数据传输。
- **io/** — `OutputWriter` 负责 JSON 和 Markdown 文件输出。

### 核心算法

- **异常检测：** MAD（中位数绝对偏差），阈值 = 中位数 + 3.0 × MAD
- **尖刺检测：** 两阶段 — 基于阈值的窗口检测（均值 × 5.0），然后提取核心点（≥ 最大值的 20%）
- **日志聚合：** 10 个维度 — 错误类型、服务路径、对端服务、网关实例、下游 IP、可用区、业务数据、客户端版本、时间桶、全局摘要

### 配置

凭据通过 Spring Boot 占位符解析（`${ENV_VAR:default}`）：
- `WATCHER_COOKIE`、`BILLIONS_COOKIE` — 内部 API 的 Cookie 认证
- `ANTHROPIC_AUTH_TOKEN`、`ANTHROPIC_BASE_URL` — Claude API 访问

输出目录：`./output/{command}/{app}_{timestamp}/`
