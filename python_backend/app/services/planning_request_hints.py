"""Deterministic request hints used by planning prompts."""

from ..models.request import BuildRequest


def _infer_scale(req: BuildRequest) -> str:
    """Infer a coarse cluster scale for planning prompts."""
    text = (req.requestText or "").lower()
    if req.selection is not None:
        dx = abs(req.selection.max.x - req.selection.min.x) + 1
        dz = abs(req.selection.max.z - req.selection.min.z) + 1
        m = max(dx, dz)
        if m <= 48:
            return "SMALL"
        if m <= 96:
            return "MEDIUM"
        return "LARGE"
    if any(k in text for k in ["large", "huge", "gigantic", "大型", "巨型", "超大"]):
        return "LARGE"
    if any(k in text for k in ["small", "tiny", "小型", "迷你"]):
        return "SMALL"
    return "MEDIUM"


def _infer_terrain(req: BuildRequest) -> str:
    """Best-effort terrain hint from user text (we do NOT sample terrain here)."""
    t = (req.requestText or "").lower()
    if any(k in t for k in ["flat", "平坦", "平地", "平原"]):
        return "FLAT"
    if any(k in t for k in ["mountain", "mountainous", "山", "高山", "山地", "峭壁"]):
        return "MOUNTAINOUS"
    if any(k in t for k in ["hill", "hilly", "丘", "丘陵", "起伏", "坡地", "崎岖"]):
        return "HILLY"
    return "UNKNOWN"
