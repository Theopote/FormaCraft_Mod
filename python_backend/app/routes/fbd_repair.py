from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from ..models.request_adapter import FormaRequestAdapter
from ..services.fbd_repair import propose_fbd_repair

router = APIRouter()


class RepairRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    request: FormaRequestAdapter
    plan: dict
    diagnostic: dict
    round: int = Field(ge=1, le=2)


@router.post('/fbd/repair')
def repair_fbd(body: RepairRequest) -> dict:
    """A proposal only; Java applies its patch guard and revalidates before preview."""
    try:
        return propose_fbd_repair(body.request.to_build_request(), body.plan, body.diagnostic, body.round)
    except ValueError:
        raise HTTPException(status_code=422, detail='FBD repair proposal rejected; original program retained')
    except Exception:
        # Provider exceptions can embed URLs or credentials; never echo them to callers.
        raise HTTPException(status_code=502, detail='FBD repair provider unavailable; original program retained')
