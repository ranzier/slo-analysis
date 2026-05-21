# SLO Agent Java

Bilibili SLO 异常自动化分析工具的 Java 版本，基于 Spring Boot 构建。

## 技术栈

- Java 17 + Spring Boot 3.4
- Gradle (Kotlin DSL)
- Spring WebClient (HTTP 客户端)
- Jackson (JSON 序列化)
- picocli (CLI 框架)

## 快速开始

### 配置

在 `src/main/resources/application-local.yml` 中填入凭证（该文件已 gitignore）：

```yaml
slo:
  watcher:
    cookie: "你的 Watcher Cookie"
  billions:
    cookie: "你的 Billions Cookie"
  ai:
    auth-token: "你的 Claude API Key"
```

或者直接复用 Python 版本的 `../slo-agent/config.json`，程序启动时会自动读取。

### 构建

```bash
./gradlew build -x test
```

### 运行

```bash
# 方式一：通过 Gradle
./gradlew bootRun --args="pipeline open.bangumi.view-gateway"

# 方式二：通过 JAR
java -jar build/libs/slo-agent-java-1.0.0.jar pipeline open.bangumi.view-gateway
```

## 命令

### pipeline — 完整自动化分析

```bash
./gradlew bootRun --args="pipeline <app_path> [options]"
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--top-days` | 2 | 分析最严重的前 N 天 |
| `--top-apis` | 3 | 每天分析前 N 个异常接口 |
| `--days` | 7 | SLO 查询天数 |
| `--skip-ai` | false | 跳过 AI 分析步骤 |

流程：SLO 日指标 → MAD 异常检测 → 错误时序抓取 → 毛刺检测 → 日志采样 → 10 维聚合 → AI 根因分析

### day — 指定日期分析

```bash
./gradlew bootRun --args="day <app_path> --date 5月18"
```

跳过异常天检测，直接分析指定日期的错误面板数据。

### compare — 基线对比

```bash
./gradlew bootRun --args="compare <app_path> --baseline 5月5 --target 5月14"
```

对比两天的错误面板数据，输出接口增量、错误码分布、热点时间段。

### diff — 增量接口发现

```bash
./gradlew bootRun --args="diff <app_path> --baseline 5月5 --target 5月14 --min-diff 50 --min-ratio 1.5"
```

找出错误数显著增长的接口，并对每个接口采集基线日/异常日日志进行多维度对比。

## 项目结构

```
src/main/java/co/bilibili/slo/
├── SloAgentApplication.java        # Spring Boot 入口
├── config/
│   ├── SloProperties.java          # 配置绑定（支持 yml + config.json 回退）
│   ├── WebClientConfig.java        # WebClient Bean 定义
│   └── JacksonConfig.java          # Jackson ObjectMapper 配置
├── model/                           # 数据模型（Java records）
│   ├── AnomalyDay.java
│   ├── ErrorSpike.java
│   ├── LogEntry.java
│   ├── ApiDiff.java
│   └── CompareResult.java
├── crawler/                         # HTTP 数据抓取
│   ├── SloCrawlerService.java       # Watcher SLO 日指标
│   ├── SloErrorCrawlerService.java  # 错误时序面板（30s 粒度）
│   └── BillionsLogCrawlerService.java # Billions 日志查询（含均匀采样）
├── analysis/                        # 纯计算分析
│   ├── AnomalyDetector.java         # MAD 异常天检测
│   ├── ErrorSpikeAnalyzer.java      # 两阶段毛刺检测
│   ├── LogAggregator.java           # 10 维日志聚合
│   └── AiAnalysisService.java       # Claude API 根因分析
├── pipeline/                        # 流水线编排
│   ├── PipelineOrchestrator.java
│   ├── PipelineDayOrchestrator.java
│   ├── PipelineCompareOrchestrator.java
│   └── IncreasedApiOrchestrator.java
├── cli/                             # picocli 命令层
│   ├── SloAgentCommand.java         # 顶层命令路由
│   ├── CliRunner.java               # Spring Boot ↔ picocli 桥接
│   ├── PipelineCommand.java
│   ├── PipelineDayCommand.java
│   ├── PipelineCompareCommand.java
│   └── FindIncreasedApisCommand.java
├── io/
│   └── OutputWriter.java            # JSON/Markdown 文件输出
└── util/
    ├── DateParser.java              # 日期解析（支持 "5月18" 等格式）
    └── ApiNameParser.java           # API 名称解析（"/path:-504" 拆分）
```

## 配置优先级

1. `application-local.yml`（本地凭证，gitignored）
2. 环境变量（`WATCHER_COOKIE`、`BILLIONS_COOKIE`、`ANTHROPIC_AUTH_TOKEN`）
3. `../slo-agent/config.json`（兼容 Python 版本配置）

## 输出目录

```
output/
├── pipeline/          # pipeline 命令输出
│   └── {app}_{timestamp}/
│       ├── slo_report.json
│       ├── anomaly_days.json
│       ├── summary.md
│       └── {date}/
│           ├── error_data.json
│           ├── error_spikes.json
│           └── {api}_{time}/
│               ├── logs.json
│               ├── aggregate.json
│               └── analysis.md
├── pipeline_day/      # day 命令输出
├── pipeline_compare/  # compare 命令输出
└── pipeline_diff/     # diff 命令输出
```

## 与 Python 版本的关系

本项目是 `slo-agent/` 的完整 Java 移植，功能和输出格式保持一致。两个版本可以共存，共享同一份 `config.json` 凭证。
