# microservice-diagnosis-agent

> 一句话定位：一个「AI 排障工程师」——用自然语言问"订单接口为什么变慢"，Agent 自动查服务状态、指标、日志、慢 SQL，输出带证据引用的根因报告。

## 目录结构

```text
microservice-diagnosis-agent/
├── tool-service/    # Spring Boot 工具服务（6 个只读诊断工具的 REST 实现，端口 8090）
├── agent-service/   # Python Agent（LangGraph 编排 + FastAPI + SSE，端口 8000）
├── demo-services/   # 被诊断的最小业务服务（order 8081 / inventory 8082，读写 MySQL+Redis）
├── eval/            # 故障注入 + 评测场景集 + 评测脚本 + 报告
├── web/             # 演示前端（手写 HTML/JS）
└── docs/            # 设计说明 / 工具契约 / 评测报告
```

各阶段的设计说明、工具契约与评测报告会陆续补充到 docs/ 下。