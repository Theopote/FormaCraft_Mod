from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Optional, Dict, Any
import json


router = APIRouter()


class SummarizeRequest(BaseModel):
    transcript: str
    apiKey: Optional[str] = None
    model: Optional[str] = None
    llmProvider: Optional[str] = None
    llmBaseUrl: Optional[str] = None
    temperature: Optional[float] = None


def _clamp_temperature(v: Optional[float], default: float) -> float:
    try:
        if v is None:
            return default
        f = float(v)
        if f < 0.0:
            return 0.0
        if f > 1.0:
            return 1.0
        return f
    except Exception:
        return default


@router.post("/summarize")
async def summarize_endpoint(req: SummarizeRequest) -> Dict[str, Any]:
    """
    对历史对话生成概要与标题。
    返回：
    {
      "title": "...",
      "summary": "..."
    }
    """
    transcript = (req.transcript or "").strip()
    if not transcript:
        return {"title": "新对话", "summary": ""}

    from ..services.llm_client import get_client, build_config
    client = get_client(req)
    if not client:
        # fallback：简单截断
        title = transcript.splitlines()[0][:28]
        if len(title) == 28:
            title = title[:-1] + "…"
        summary = transcript[:180]
        if len(transcript) > 180:
            summary = summary[:-1] + "…"
        return {"title": title or "新对话", "summary": summary}

    system_prompt = (
        "You are FormaCraft. Summarize a Minecraft building conversation.\n"
        "Return ONLY a JSON object with keys: title, summary.\n"
        "Rules:\n"
        "- title: short, <= 12 Chinese characters (or <= 24 latin chars).\n"
        "- summary: 1-2 sentences, Chinese.\n"
        "- Do not include markdown.\n"
    )

    user_prompt = f"Conversation transcript:\n\n{transcript}\n"

    try:
        cfg = build_config(req, default_model="gpt-4o-mini")
        resp = client.chat.completions.create(
            model=cfg.model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            response_format={"type": "json_object"},
            temperature=_clamp_temperature(req.temperature, 0.3),
        )
        raw = resp.choices[0].message.content
        if not raw:
            raise ValueError("empty response")
        data = json.loads(raw)
        title = str(data.get("title", "")).strip() or "新对话"
        summary = str(data.get("summary", "")).strip()
        return {"title": title, "summary": summary}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to summarize: {str(e)}")

