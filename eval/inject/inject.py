#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
微服务智能诊断 Agent · 故障注入脚本
============================================================

用途
----
向「被诊断环境」的 demo 业务服务（order-service 8081 / inventory-service 8082）
注入受控故障，为诊断 Agent 的评测场景制造"已知根因"。

本脚本只依赖 Python 标准库（urllib），不需要任何第三方包。

子命令
------
    list       列出目标服务及其当前故障状态
    latency    注入「延迟」故障（模拟下游变慢 / 线程阻塞）
    exception  注入「异常」故障（模拟代码缺陷抛错）
    status     查询当前故障
    clear      清除故障

常用示例
--------
    # 1) 查看目标与当前状态
    python eval/inject/inject.py list

    # 2) 给订单服务所有业务接口注入 3 秒延迟（模拟下游依赖变慢）
    python eval/inject/inject.py latency --service order --path-regex "/api/.*" --latency-ms 3000

    # 3) 给库存服务注入 50% 概率的异常（模拟异常率上升）
    python eval/inject/inject.py exception --service inventory --path-regex "/api/.*" --percent 50 --message "库存扣减内部错误"

    # 4) 查询 / 清除
    python eval/inject/inject.py status --service order
    python eval/inject/inject.py clear --service order
    python eval/inject/inject.py clear            # 不带 --service 时清理全部目标

约束（与后端实现一致）
----------------------
    - 同一时刻每个服务只允许一个活动故障；重复注入返回 409，
      需先 clear 再注入（保证评测"单一变量"）。
    - /internal/** 端点永不注入（health/metrics/fault 始终可用）。
    - percent=100 表示每次都命中（评测默认，保证可复现）。
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

# ============================================================
# 目标服务注册表（当前为单实例；后续多实例时按需扩展为列表）
# key = 服务名（也接受短名 order / inventory 作为别名）
# ============================================================
TARGETS = {
    "order-service": "http://127.0.0.1:8081",
    "inventory-service": "http://127.0.0.1:8082",
}

# 短名别名：命令行里写 order / inventory 更顺手
ALIASES = {
    "order": "order-service",
    "inventory": "inventory-service",
}

# HTTP 调用超时（秒）：注入接口本身必须快速返回，超时说明环境有问题
DEFAULT_TIMEOUT_SECONDS = 5.0


def resolve_target(name: str) -> str:
    """把用户输入的服务名（含短名别名）解析为规范服务名。"""
    normalized = (name or "").strip().lower()
    normalized = ALIASES.get(normalized, normalized)
    if normalized not in TARGETS:
        valid = "、".join(sorted(TARGETS.keys()))
        raise SystemExit(f"[错误] 未知服务: {name}；可用服务: {valid}（短名: order / inventory）")
    return normalized


def resolve_targets(name: str | None) -> list:
    """解析目标服务列表：未指定则返回全部。"""
    if not name:
        return sorted(TARGETS.keys())
    return [resolve_target(name)]


def http_request(method: str, url: str, body: dict | None = None,
                 timeout: float = DEFAULT_TIMEOUT_SECONDS) -> tuple:
    """
    发起 HTTP 请求（仅标准库）。

    返回 (status_code, response_obj)；response_obj 为解析后的 JSON（解析失败则为原始文本）。
    网络不可达等异常返回 (None, 错误描述)，由调用方决定如何处理。
    """
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"

    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            return response.status, _parse_json(raw)
    except urllib.error.HTTPError as e:
        # 服务端明确返回了 4xx/5xx（例如 409 已有活动故障）：把响应体带回去供打印
        raw = e.read().decode("utf-8", errors="replace")
        return e.code, _parse_json(raw)
    except urllib.error.URLError as e:
        return None, f"无法连接: {e.reason}"
    except TimeoutError:
        return None, "请求超时"


def _parse_json(raw: str):
    """尝试解析 JSON；失败时返回原始字符串。"""
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return raw


def print_json(obj) -> None:
    """打印 JSON（中文直出；终端编码不支持时回退为 ASCII 转义）。"""
    text = json.dumps(obj, ensure_ascii=False, indent=2)
    try:
        print(text)
    except UnicodeEncodeError:
        print(json.dumps(obj, ensure_ascii=True, indent=2))


# ============================================================
# 子命令实现
# ============================================================

def cmd_list(args) -> int:
    """list：列出目标服务 + 当前故障状态（先探测可达性，便于手动自测）。"""
    result = {}
    for service in resolve_targets(None):
        status, payload = http_request("GET", f"{TARGETS[service]}/internal/fault")
        if status is None:
            result[service] = {"reachable": False, "detail": payload}
        else:
            result[service] = {"reachable": True, "baseCheck": "8081/8082 已响应", "fault": payload}
    print_json(result)
    return 0


def cmd_status(args) -> int:
    """status：查询指定/全部服务的当前故障。"""
    result = {}
    exit_code = 0
    for service in resolve_targets(args.service):
        status, payload = http_request("GET", f"{TARGETS[service]}/internal/fault")
        if status is None:
            result[service] = {"error": payload}
            exit_code = 1
        else:
            result[service] = payload
    print_json(result)
    return exit_code


def _inject(service: str, spec: dict) -> int:
    """向单个服务 POST /internal/fault；409（已有故障）时打印原因与清除提示。"""
    status, payload = http_request("POST", f"{TARGETS[service]}/internal/fault", body=spec)
    if status is None:
        print(f"[错误] {service} 注入失败：{payload}")
        return 1
    if status == 409:
        print(f"[冲突] {service} 已有活动故障（同一时刻只允许一个），请先执行: "
              f"python eval/inject/inject.py clear --service {service}")
        print_json(payload)
        return 2
    if status != 200:
        print(f"[错误] {service} 注入失败，HTTP {status}")
        print_json(payload)
        return 1
    print(f"[成功] 已向 {service} 注入故障：")
    print_json(payload)
    return 0


def cmd_latency(args) -> int:
    """latency：注入延迟故障。"""
    spec = {
        "type": "latency",
        "pathRegex": args.path_regex,
        "percent": args.percent,
        "latencyMs": args.latency_ms,
    }
    exit_code = 0
    for service in resolve_targets(args.service):
        exit_code = max(exit_code, _inject(service, spec))
    return exit_code


def cmd_exception(args) -> int:
    """exception：注入异常故障。"""
    spec = {
        "type": "exception",
        "pathRegex": args.path_regex,
        "percent": args.percent,
        "message": args.message,
    }
    exit_code = 0
    for service in resolve_targets(args.service):
        exit_code = max(exit_code, _inject(service, spec))
    return exit_code


def cmd_clear(args) -> int:
    """clear：清除指定/全部服务的故障。"""
    result = {}
    exit_code = 0
    for service in resolve_targets(args.service):
        status, payload = http_request("DELETE", f"{TARGETS[service]}/internal/fault")
        if status is None:
            result[service] = {"error": payload}
            exit_code = 1
        else:
            result[service] = payload
    print_json(result)
    return exit_code


# ============================================================
# 参数解析
# ============================================================

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="inject.py",
        description="微服务智能诊断 Agent · 故障注入脚本（仅标准库）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n"
               "  python eval/inject/inject.py list\n"
               "  python eval/inject/inject.py latency --service order --path-regex \"/api/.*\" --latency-ms 3000\n"
               "  python eval/inject/inject.py exception --service inventory --path-regex \"/api/.*\" --percent 50\n"
               "  python eval/inject/inject.py status\n"
               "  python eval/inject/inject.py clear --service order\n")
    sub = parser.add_subparsers(dest="command", required=True)

    # list
    sub.add_parser("list", help="列出目标服务与当前故障状态")

    # latency
    p_latency = sub.add_parser("latency", help="注入延迟故障")
    p_latency.add_argument("--service", help="目标服务（order / inventory，缺省=全部）")
    p_latency.add_argument("--path-regex", default="/api/.*",
                           help="路径正则（整体匹配），默认 /api/.*（所有业务接口）")
    p_latency.add_argument("--latency-ms", type=int, default=3000,
                           help="注入延迟毫秒数（1~10000），默认 3000")
    p_latency.add_argument("--percent", type=int, default=100,
                           help="命中概率 0~100，默认 100（评测用 100 保证可复现）")

    # exception
    p_exception = sub.add_parser("exception", help="注入异常故障")
    p_exception.add_argument("--service", help="目标服务（order / inventory，缺省=全部）")
    p_exception.add_argument("--path-regex", default="/api/.*",
                             help="路径正则（整体匹配），默认 /api/.*（所有业务接口）")
    p_exception.add_argument("--percent", type=int, default=100,
                             help="命中概率 0~100，默认 100")
    p_exception.add_argument("--message", default="injected-exception",
                             help="注入异常消息（用于日志指纹识别）")

    # status
    p_status = sub.add_parser("status", help="查询当前故障")
    p_status.add_argument("--service", help="目标服务（缺省=全部）")

    # clear
    p_clear = sub.add_parser("clear", help="清除故障")
    p_clear.add_argument("--service", help="目标服务（缺省=全部）")

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    handlers = {
        "list": cmd_list,
        "latency": cmd_latency,
        "exception": cmd_exception,
        "status": cmd_status,
        "clear": cmd_clear,
    }
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())