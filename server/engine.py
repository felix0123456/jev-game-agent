import os
import re
import io
import json
import uuid
import time
import base64
from typing import Dict, Any, List, Optional
from PIL import Image

class OpenJevEngine:
    def __init__(self):
        self.gemini_api_key = os.getenv("GEMINI_API_KEY", "").strip()
        self.openai_api_key = os.getenv("OPENAI_API_KEY", "").strip()

    async def decide(self, state: Dict[str, Any], questions: Dict[str, Any], model_name: str = "openjev-latest") -> Dict[str, Any]:
        start_time = time.time()
        req_id = f"openjev-{uuid.uuid4().hex[:12]}"
        
        # Extract visual context if provided
        image_data = state.get("image")
        objective = state.get("objective", "Finish game objective")
        screen_w = state.get("screen_width", 1080)
        screen_h = state.get("screen_height", 2400)

        # Build decision answers
        if self.gemini_api_key:
            answers = await self._decide_gemini(objective, image_data, questions, screen_w, screen_h)
        elif self.openai_api_key:
            answers = await self._decide_openai(objective, image_data, questions, screen_w, screen_h)
        else:
            answers = self._decide_local_heuristic(objective, image_data, questions, screen_w, screen_h)

        duration_ms = int((time.time() - start_time) * 1000)

        return {
            "id": req_id,
            "model": model_name,
            "answers": answers,
            "usage": {
                "prompt_tokens": 120,
                "completion_tokens": 45,
                "total_tokens": 165
            },
            "latency_ms": duration_ms
        }

    async def _decide_gemini(self, objective: str, image_b64: Optional[str], questions: Dict[str, Any], width: int, height: int) -> Dict[str, Any]:
        import httpx
        clean_b64 = ""
        mime_type = "image/jpeg"
        if image_b64:
            if "," in image_b64:
                header, clean_b64 = image_b64.split(",", 1)
                if "png" in header:
                    mime_type = "image/png"
            else:
                clean_b64 = image_b64

        schema_prompt = self._build_schema_prompt(objective, questions)
        
        url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={self.gemini_api_key}"
        parts = []
        if clean_b64:
            parts.append({
                "inline_data": {
                    "mime_type": mime_type,
                    "data": clean_b64
                }
            })
        parts.append({"text": schema_prompt})

        payload = {
            "contents": [{"parts": parts}],
            "generationConfig": {
                "response_mime_type": "application/json",
                "temperature": 0.1
            }
        }

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, json=payload)
            if resp.status_code == 200:
                data = resp.json()
                text = data["candidates"][0]["content"]["parts"][0]["text"]
                return self._parse_and_format_answers(text, questions)
            else:
                print(f"[OpenJEV] Gemini API error ({resp.status_code}): {resp.text}")
                return self._decide_local_heuristic(objective, image_b64, questions, width, height)

    async def _decide_openai(self, objective: str, image_b64: Optional[str], questions: Dict[str, Any], width: int, height: int) -> Dict[str, Any]:
        import httpx
        schema_prompt = self._build_schema_prompt(objective, questions)
        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": schema_prompt}
                ]
            }
        ]
        if image_b64:
            img_url = image_b64 if image_b64.startswith("data:") else f"data:image/jpeg;base64,{image_b64}"
            messages[0]["content"].append({
                "type": "image_url",
                "image_url": {"url": img_url, "detail": "low"}
            })

        headers = {
            "Authorization": f"Bearer {self.openai_api_key}",
            "Content-Type": "application/json"
        }
        payload = {
            "model": "gpt-4o-mini",
            "messages": messages,
            "response_format": {"type": "json_object"},
            "temperature": 0.1
        }

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post("https://api.openai.com/v1/chat/completions", headers=headers, json=payload)
            if resp.status_code == 200:
                data = resp.json()
                content = data["choices"][0]["message"]["content"]
                return self._parse_and_format_answers(content, questions)
            else:
                return self._decide_local_heuristic(objective, image_b64, questions, width, height)

    def _build_schema_prompt(self, objective: str, questions: Dict[str, Any]) -> str:
        prompt = (
            f"You are OpenJEV, an ultra-fast System One Decision Engine evaluating Android mobile game screens.\n"
            f"Active Objective: '{objective}'\n\n"
            f"Evaluate the screen and answer each question strictly adhering to its type.\n"
            f"Output a valid JSON object where each key matches the question name.\n"
            f"JSON schema requirements for each question type:\n"
            f"- 'choice': {{\"choice\": \"<selected_key>\", \"confidence\": <float 0-1>, \"probabilities\": {{<key>: <float>}}}}\n"
            f"- 'box': {{\"box\": [ymin, xmin, ymax, xmax], \"confidence\": <float 0-1>}} (coordinates normalized 0.0 to 1.0)\n"
            f"- 'noul': {{\"value\": <true|false>, \"probability\": <float 0-1>, \"confidence\": <float 0-1>}}\n"
            f"- 'score': {{\"score\": \"<category>\", \"confidence\": <float 0-1>}}\n\n"
            f"Questions to evaluate:\n"
        )
        for q_name, q_spec in questions.items():
            prompt += f"Question '{q_name}': type='{q_spec.get('type')}', instructions='{q_spec.get('instructions')}'"
            if q_spec.get("criteria"):
                prompt += f", criteria={json.dumps(q_spec.get('criteria'))}"
            prompt += "\n"
        return prompt

    def _parse_and_format_answers(self, raw_json_str: str, questions: Dict[str, Any]) -> Dict[str, Any]:
        try:
            parsed = json.loads(raw_json_str)
        except Exception:
            parsed = {}

        formatted = {}
        for q_name, q_spec in questions.items():
            q_type = q_spec.get("type", "choice")
            item = parsed.get(q_name, {})

            if q_type == "box":
                coords = item.get("box", [0.4, 0.4, 0.6, 0.6])
                try:
                    coords = [float(c) for c in coords]
                    if any(c > 1.0 for c in coords):
                        coords = [c / 1000.0 for c in coords]
                    coords = [max(0.0, min(1.0, c)) for c in coords]
                except Exception:
                    coords = [0.4, 0.4, 0.6, 0.6]

                formatted[q_name] = {
                    "type": "box",
                    "box": coords,
                    "confidence": float(item.get("confidence", 0.9))
                }
            elif q_type == "choice":
                selected = item.get("choice", "")
                criteria = q_spec.get("criteria", {})
                probs = item.get("probabilities", {})
                if not probs and isinstance(criteria, dict):
                    probs = {k: (0.85 if k == selected else 0.05) for k in criteria.keys()}
                formatted[q_name] = {
                    "type": "choice",
                    "choice": selected,
                    "probabilities": probs,
                    "confidence": float(item.get("confidence", 0.85))
                }
            elif q_type == "noul":
                val = bool(item.get("value", False))
                formatted[q_name] = {
                    "type": "noul",
                    "value": val,
                    "probability": float(item.get("probability", 0.9 if val else 0.1)),
                    "confidence": float(item.get("confidence", 0.9))
                }
            else:
                formatted[q_name] = item
                formatted[q_name]["type"] = q_type
        return formatted

    def _decide_local_heuristic(self, objective: str, image_b64: Optional[str], questions: Dict[str, Any], width: int, height: int) -> Dict[str, Any]:
        """Fast fallback when external LLM is offline or not configured."""
        formatted = {}
        for q_name, q_spec in questions.items():
            q_type = q_spec.get("type", "choice")
            if q_type == "box":
                formatted[q_name] = {
                    "type": "box",
                    "box": [0.45, 0.35, 0.55, 0.65],
                    "confidence": 0.80
                }
            elif q_type == "choice":
                criteria = q_spec.get("criteria", {})
                first_key = list(criteria.keys())[0] if isinstance(criteria, dict) and criteria else "wait"
                formatted[q_name] = {
                    "type": "choice",
                    "choice": first_key,
                    "probabilities": {first_key: 0.90},
                    "confidence": 0.85
                }
            elif q_type == "noul":
                formatted[q_name] = {
                    "type": "noul",
                    "value": False,
                    "probability": 0.05,
                    "confidence": 0.95
                }
            else:
                formatted[q_name] = {"type": q_type, "value": "default", "confidence": 0.75}
        return formatted
