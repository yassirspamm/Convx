# Android Performance Optimization Skill

An expert-level AI Agent skill for diagnosing and debugging Android application performance issues. This skill equips your agent (Cursor, Windsurf, Claude Code) with deep knowledge on ANRs, crashes, app startup time, UI jank, memory leaks, and more.

## Installation

This skill is published on [`skills.sh`](https://skills.sh/) and can be installed seamlessly using the CLI.

Run the following command in your agent's terminal or project root:

```bash
npx skills add avinashbest/android-performance-skill
```

## How to trigger this skill

Once installed, your AI agent will automatically refer to this skill whenever you ask about Android performance or debugging. You can trigger it with prompts like:
- "My app is slow"
- "Users are complaining about crashes"
- "ANR in Play Console"
- "Startup is taking too long"

## Capabilities
The agent is trained to diagnose first, provide code-backed fixes, and suggest tooling for further measurement on the following domains:
- **ANRs & Crashes:** Application Not Responding, OOM, NullPointerExceptions.
- **App Startup:** Cold start, warm start, hot start optimization, TTID, TTFD.
- **UI & Rendering:** Overdraw, RecyclerView optimization, fixing frame drops.
- **Memory & Leak Management:** Heap optimization, finding leaks with LeakCanary.
- **Threading & Coroutines:** Safely managing work off the main thread.
- **Build & Code Size:** ProGuard/R8 configuration, Baseline Profiles.
- **Profiling Tools:** Android Studio Profiler, Perfetto, Systrace integration.
