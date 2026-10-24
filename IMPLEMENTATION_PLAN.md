# Android Autonomous Game Agent (Jev-Powered) — Implementation Plan

## 1. System Overview

This application operates as an on-device Android companion/overlay service that:
1. Takes a natural-language gaming objective as input (e.g., *"Finish this level in Subway Surfers"*, *"Finish Mission 4 in Genshin Impact"*).
2. Continuously reads the screen buffer while the game is running in the foreground via Android's `MediaProjection` API.
3. Leverages **TypeSafe AI's Jev** model as a high-speed "System One" decision engine (70–500 ms) producing calibrated typed decisions (`choice`, `box`, `score`, and `noul`).
4. Injects real-time touch, swipe, hold, and tap gestures into the active game using an Android `AccessibilityService` or `Shizuku`.
5. Verifies when the objective has been reached to terminate the session automatically.

---

## 2. Architecture & Modules

```
┌─────────────────────────────────────────────────────────────┐
│                      Android System                         │
│                                                             │
│   ┌───────────────────┐            ┌────────────────────┐   │
│   │ Floating HUD / UI │            │    Target Game     │   │
│   │ (Alert Window)    │            │  (OpenGL / Vulkan) │   │
│   └─────────┬─────────┘            └────────▲───────────┘   │
│             │ Objective Input               │ Touch Gestures│
│   ┌─────────▼───────────────────────────────┴───────────┐   │
│   │               Agent Controller                      │   │
│   │  - State Machine (Lobby / In-Game / Victory)        │   │
│   │  - Safety & Emergency Kill-Switch                   │   │
│   └─────────▲───────────────────────────────▲───────────┘   │
│             │ Screen Frames                 │ Action Boxes  │
│   ┌─────────┴─────────┐            ┌────────┴───────────┐   │
│   │  MediaProjection  │            │     Jev Engine     │   │
│   │   Screen Capture  │            │   (TypeSafe AI)    │   │
│   └───────────────────┘            └────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Module Breakdown
1. **`app/overlay`**: Floating draggable bubble and HUD overlay. Contains the objective input field, start/pause/stop toggles, and live bounding-box preview.
2. **`app/vision`**: `MediaProjection` foreground service capturing downsampled game frames (720p/512px WebP) at 5–15 FPS.
3. **`app/jev`**: Network client communicating with TypeSafe AI's Jev API (`/v1/systemone`) using typed decision schemas (`box` coordinates, `choice` actions, `noul` goal-complete verification).
4. **`app/input`**: Gesture synthesizer (`AccessibilityService.dispatchGesture` or `Shizuku`) generating humanized touches with randomized Bézier curves.
5. **`app/core`**: Main orchestrator managing the game lifecycle (Lobby Navigation -> Active Play -> Victory/Retry -> Completion).

---

## 3. Jev API Decision Contract

For every captured frame, the agent issues a typed question payload to Jev:

```json
{
  "model": "typesafe/jev-latest",
  "state": {
    "objective": "Finish level 4 in Example Game",
    "phase": "IN_GAME_RUNNING",
    "image": "<base64_frame>"
  },
  "questions": {
    "game_status": {
      "type": "choice",
      "instructions": "What is the current game screen?",
      "criteria": {
        "in_progress": "gameplay is actively happening",
        "level_complete": "level complete / victory banner shown",
        "game_over": "defeat / retry screen shown",
        "menu": "lobby, settings, or pause screen"
      }
    },
    "action_type": {
      "type": "choice",
      "instructions": "What action should be taken right now?",
      "criteria": {
        "tap_target": "tap a specific object, enemy, or button",
        "swipe_lane": "swipe to evade or change lanes",
        "wait": "no immediate action required"
      }
    },
    "target_box": {
      "type": "box",
      "instructions": "Bounding box of the element/button to interact with"
    },
    "swipe_direction": {
      "type": "choice",
      "instructions": "If swipe is needed, which direction?",
      "criteria": {
        "up": "jump / up",
        "down": "slide / down",
        "left": "left",
        "right": "right",
        "none": "none"
      }
    },
    "objective_fulfilled": {
      "type": "noul",
      "instructions": "Has the user's objective been achieved?"
    }
  }
}
```

---

## 4. Implementation Phases

| Phase | Milestone | Deliverables |
|---|---|---|
| **Phase 1** | Project Setup & Permissions | Jetpack Compose app, MediaProjection request, Accessibility Service setup |
| **Phase 2** | Floating HUD & Kill-Switch | `SYSTEM_ALERT_WINDOW` draggable pill, goal input modal, instant stop button |
| **Phase 3** | Screen Capture Engine | Fast virtual display capture, memory pooling, < 25ms frame pipeline |
| **Phase 4** | Jev Client & Decision Schema | Retrofit client for TypeSafe AI Jev API, typed model deserialization, confidence filters |
| **Phase 5** | Input Injection & Autopilot | `dispatchGesture` integration, lobby & in-game reactive loop, objective completion trigger |
| **Phase 6** | Anti-Bot & Robustness | Bézier curves, touch jitter, manual user intervention detection, error recovery |
