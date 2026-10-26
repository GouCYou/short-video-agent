"""Run the same prompts against direct, Agent, and Agent+RAG; JSONL evidence."""
import argparse
import asyncio
import json
import random
import statistics
import time
from pathlib import Path
import httpx
from .config import Settings
from .knowledge import Knowledge
from .models import ContentOutput, GenerateRequest, TaskInput
from .providers import Provider
from .workflow import Workflow


async def evaluate(args):
    cfg = Settings.from_env()
    if cfg.mode != "real" and not args.allow_demo:
        raise SystemExit("评测默认只允许 AI_MODE=real。功能测试可加 --allow-demo，结果将标注不用于论文效果评测。")
    if not cfg.generation_configured:
        raise SystemExit("请先配置 GENERATION_API_KEY、GENERATION_BASE_URL 和 GENERATION_MODEL。")
    inputs = json.loads(Path(args.input).read_text(encoding="utf-8"))
    if not isinstance(inputs, list) or not inputs:
        raise SystemExit("输入应为非空 TaskInput 数组。")
    tasks = [TaskInput.model_validate(task) for task in inputs]
    jobs = [(index, repeat, mode) for index in range(len(tasks)) for repeat in range(args.repeats)
            for mode in ("direct", "agent", "agent_rag")]
    random.Random(args.seed).shuffle(jobs)
    path = Path(args.output)
    path.parent.mkdir(parents=True, exist_ok=True)
    records = []
    async with httpx.AsyncClient(timeout=cfg.timeout) as client:
        provider = Provider(cfg, client)
        knowledge = Knowledge(cfg, provider, client)
        workflow = Workflow(cfg, provider, knowledge)
        with path.open("w", encoding="utf-8") as output:
            for index, repeat, mode in jobs:
                task = tasks[index].model_copy(update={"ragEnabled": mode == "agent_rag", "mode": "QUICK"})
                record = {"sampleIndex": index, "repeat": repeat, "method": mode, "aiMode": cfg.mode,
                          "validForQualityEvaluation": cfg.mode == "real", "task": task.model_dump()}
                started = time.perf_counter()
                try:
                    if mode == "direct":
                        if cfg.mode == "demo":
                            from .demo import content
                            result = content(GenerateRequest(taskId="eval", phase="GENERATE", task=task))
                            usage = {"inputTokens": 0, "outputTokens": 0, "model": "DEMO-FIXTURE"}
                        else:
                            result, usage = await asyncio.wait_for(provider.structured(
                                ContentOutput, "根据需求一次性生成完整短视频策划、脚本、标题和发布文案。"
                                "不提供外部资料，usedChunkIds 必须为空。事实需人工核实。分段时长总和等于目标时长。\n" +
                                json.dumps(task.model_dump(), ensure_ascii=False)), timeout=300)
                        if result.usedChunkIds:
                            raise ValueError("Direct baseline returned unsupported source IDs")
                        record.update({"result": result.model_dump(), "usage": usage, "success": True})
                    else:
                        result = await asyncio.wait_for(workflow.run(GenerateRequest(taskId=f"eval-{index}", phase="GENERATE", task=task)), timeout=300)
                        record.update({"result": result, "usage": result["usage"], "success": True})
                except Exception as exc:
                    record.update({"success": False, "error": getattr(exc, "message", type(exc).__name__),
                                   "errorCode": getattr(exc, "code", type(exc).__name__)})
                record["durationMs"] = round((time.perf_counter() - started) * 1000)
                records.append(record)
                output.write(json.dumps(record, ensure_ascii=False) + "\n")
                output.flush()
                print(f"{len(records)}/{len(jobs)} sample={index} method={mode} success={record['success']}")
    summary = {"aiMode": cfg.mode, "model": cfg.generation_model if cfg.mode == "real" else "DEMO-FIXTURE",
               "validForQualityEvaluation": cfg.mode == "real", "seed": args.seed, "repeats": args.repeats,
               "manualScoringRequired": ["主题相关性", "完整性", "风格适配", "时长适配", "事实依据", "人工修改量"], "methods": {}}
    for mode in ("direct", "agent", "agent_rag"):
        rows = [row for row in records if row["method"] == mode]
        summary["methods"][mode] = {"runs": len(rows), "successRate": sum(row["success"] for row in rows) / len(rows),
                                    "meanDurationMs": round(statistics.mean(row["durationMs"] for row in rows)),
                                    "inputTokens": sum(row.get("usage", {}).get("inputTokens", 0) for row in rows),
                                    "outputTokens": sum(row.get("usage", {}).get("outputTokens", 0) for row in rows)}
    path.with_suffix(".summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default="examples/evaluation.json")
    parser.add_argument("--output", default="evaluation/results.jsonl")
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--seed", type=int, default=2027)
    parser.add_argument("--allow-demo", action="store_true")
    options = parser.parse_args()
    if not 1 <= options.repeats <= 10:
        parser.error("--repeats must be between 1 and 10")
    asyncio.run(evaluate(options))

