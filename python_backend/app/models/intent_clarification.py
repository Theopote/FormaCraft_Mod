"""Clarification assessment when user build intent is too vague."""

from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Field


class ClarificationAssessment(BaseModel):
    needs_clarification: bool = False
    reason: Optional[str] = None
    questions_zh: List[str] = Field(default_factory=list)
    message_zh: Optional[str] = None
    confidence: float = Field(default=0.5, ge=0.0, le=1.0)
