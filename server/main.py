import os
import time
from typing import Dict, Any, Optional
from fastapi import FastAPI, HTTPException, Header, Depends, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from engine import OpenJevEngine

app = FastAPI(
    title="OpenJEV System One Decision Server",
    description="High-speed System One decision engine for Android game automation and UI control",
    version="1.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

engine = OpenJevEngine()
SERVER_START_TIME = time.time()
TOTAL_REQUESTS = 0
LAST_LATENCY = 0

class DecisionRequest(BaseModel):
    model: Optional[str] = "openjev-latest"
    state: Dict[str, Any]
    questions: Dict[str, Any]

@app.get("/health")
async def health():
    return {
        "status": "healthy",
        "service": "OpenJEV",
        "uptime_seconds": int(time.time() - SERVER_START_TIME),
        "total_requests": TOTAL_REQUESTS,
        "engine_backend": "Gemini-2.0-Flash" if engine.gemini_api_key else ("OpenAI" if engine.openai_api_key else "Heuristic-Local")
    }

@app.post("/v1/systemone")
@app.post("/api/v1/decide")
async def evaluate_decision(req: DecisionRequest, authorization: Optional[str] = Header(None)):
    global TOTAL_REQUESTS, LAST_LATENCY
    TOTAL_REQUESTS += 1
    
    expected_api_key = os.getenv("OPENJEV_API_KEY", "").strip()
    if expected_api_key:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="Bearer token required")
        token = authorization.split("Bearer ", 1)[1].strip()
        if token != expected_api_key:
            raise HTTPException(status_code=403, detail="Invalid API key")

    result = await engine.decide(req.state, req.questions, req.model or "openjev-latest")
    LAST_LATENCY = result.get("latency_ms", 0)
    return result

@app.get("/", response_class=HTMLResponse)
async def dashboard():
    uptime = int(time.time() - SERVER_START_TIME)
    backend = "Gemini Flash (Vision)" if engine.gemini_api_key else ("OpenAI Vision" if engine.openai_api_key else "Local Engine")
    
    html = f"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <title>⚡ OpenJEV Decision Server</title>
        <style>
            body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 40px; }}
            .container {{ max-width: 800px; margin: 0 auto; background: #1e293b; padding: 32px; border-radius: 16px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); }}
            h1 {{ color: #10b981; margin-top: 0; font-size: 2.2rem; }}
            .badge {{ display: inline-block; padding: 4px 12px; border-radius: 20px; font-weight: bold; font-size: 0.85rem; }}
            .badge-live {{ background: #064e3b; color: #34d399; border: 1px solid #059669; }}
            .stat-grid {{ display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin: 24px 0; }}
            .stat-card {{ background: #334155; padding: 16px; border-radius: 10px; text-align: center; }}
            .stat-val {{ font-size: 1.8rem; font-weight: bold; color: #38bdf8; }}
            .stat-lbl {{ font-size: 0.8rem; color: #94a3b8; text-transform: uppercase; margin-top: 4px; }}
            pre {{ background: #0f172a; padding: 16px; border-radius: 8px; overflow-x: auto; font-size: 0.9rem; color: #a5f3fc; }}
            code {{ color: #f472b6; }}
        </style>
    </head>
    <body>
        <div class="container">
            <h1>⚡ OpenJEV System One</h1>
            <p><span class="badge badge-live">● OPERATIONAL</span> Running high-speed decision evaluation for Android gaming.</p>
            
            <div class="stat-grid">
                <div class="stat-card">
                    <div class="stat-val">{backend}</div>
                    <div class="stat-lbl">Active Vision Engine</div>
                </div>
                <div class="stat-card">
                    <div class="stat-val">{TOTAL_REQUESTS}</div>
                    <div class="stat-lbl">Total Decisions</div>
                </div>
                <div class="stat-card">
                    <div class="stat-val">{LAST_LATENCY} ms</div>
                    <div class="stat-lbl">Last Latency</div>
                </div>
            </div>

            <h3>API Integration</h3>
            <p>Android Game Agent configuration endpoint:</p>
            <pre>POST https://jev.felixresch.com/v1/systemone</pre>
            
            <h3>Supported Question Types</h3>
            <ul>
                <li><code>box</code>: Normalized bounding coordinates [ymin, xmin, ymax, xmax] for UI elements/enemies</li>
                <li><code>choice</code>: Categorical decision probabilities over criteria</li>
                <li><code>noul</code>: Calibrated probability (Yes/No) for objective completion verification</li>
            </ul>
        </div>
    </body>
    </html>
    """
    return html

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
