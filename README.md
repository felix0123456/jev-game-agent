# Android Game Agent (Jev-Powered)

An autonomous Android agent that takes high-level gaming objectives as input (e.g. *"Finish this level in Subway Surfers"*, *"Finish Mission 3 in RAID"*), observes the device screen in real time, and uses **TypeSafe AI's Jev** decision engine to autonomously complete the objective.

Full detailed architecture and implementation plan is available in [IMPLEMENTATION_PLAN.md](file:///c:/Users/felix/OneDrive/Dokumente/workspace/Projects/Apps/jev%20game/IMPLEMENTATION_PLAN.md).

## Core Architecture
- **Perception**: Android `MediaProjection` capturing active game frames.
- **Decision Engine**: TypeSafe AI Jev ("System One" typed decisions with calibrated `box`, `choice`, and `noul` outputs in sub-second intervals).
- **Execution**: Android `AccessibilityService` (`dispatchGesture`) or Shizuku for zero-delay touch injection.
- **UI**: Floating HUD overlay (`SYSTEM_ALERT_WINDOW`) with live bounding box visualizer and emergency kill-switch.
