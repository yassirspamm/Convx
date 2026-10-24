---
name: android-performance-skill
description: >
  Expert-level Android performance optimization skill. Use this skill whenever the user asks about
  anything related to Android app performance — including but not limited to: ANRs (Application Not
  Responding errors), crashes (NullPointerException, OOM, fatal exceptions), app startup time (cold
  start, warm start, hot start), UI jank or frame drops, memory leaks, battery drain, slow network
  calls, database/SQLite performance, overdraw, ProGuard/R8, Doze mode, WorkManager, RecyclerView
  performance, Bitmap/image loading, threading/coroutines, profiling with Android Studio, Perfetto,
  or Systrace. Trigger even if the user just mentions "my app is slow", "users are complaining about
  crashes", "ANR in Play Console", "startup is taking too long", or any vague performance complaint
  in an Android context. Always use this skill before answering Android performance questions — never
  rely on general memory alone.
---

# Android Performance Optimization Skill

## Quick Domain Index

Load the relevant reference file(s) based on the user's query. Multiple may apply.

| Topic | Reference File | Keywords |
|---|---|---|
| ANRs | `references/anr.md` | ANR, Application Not Responding, main thread, blocked, 5 seconds, input dispatch |
| Crashes | `references/crashes.md` | crash, exception, OOM, NullPointer, fatal, Play Console vitals |
| App Startup | `references/startup.md` | cold start, warm start, hot start, TTID, TTFD, splash screen, AppStartup |
| UI & Rendering | `references/ui-rendering.md` | jank, frame drop, overdraw, RecyclerView, Choreographer, 16ms, 60fps, 90fps |
| Memory | `references/memory.md` | memory leak, OOM, heap, GC, LeakCanary, Bitmap, LargeHeap |
| Threading & Coroutines | `references/threading.md` | coroutines, threads, Dispatchers, StrictMode, main thread, background work |
| Network & Data | `references/network.md` | network, HTTP, OkHttp, Retrofit, caching, Gzip, payload size |
| Battery & Background | `references/battery.md` | battery, Doze, WorkManager, AlarmManager, wakelock, background |
| Database & Storage | `references/database.md` | Room, SQLite, query, index, SharedPreferences, DataStore |
| Build & Code Size | `references/build-size.md` | ProGuard, R8, APK size, app bundle, baseline profiles, dexopt |
| Profiling Tools | `references/profiling.md` | Android Studio Profiler, Perfetto, Systrace, Macrobenchmark, Microbenchmark, Tracing |

---

## Response Philosophy

1. **Diagnose first** — Ask for or infer: device API level, whether it's debug/release, reproduction steps, any stack traces or Play Console data. Don't prescribe fixes before understanding root cause.
2. **Show, don't just tell** — Always include concrete Kotlin/Java code examples, not just advice.
3. **Layered recommendations** — Start with the highest-impact, easiest fix. Then go deeper.
4. **Measurability** — Every recommendation should include how to measure before/after (tooling, metrics).
5. **Trade-offs** — Be honest about trade-offs (e.g., aggressive caching vs memory pressure).

---

## Universal Performance Principles (always applicable)

- **Never block the main thread.** The Main/UI thread must complete each frame in <16ms (60fps) or <11ms (90fps). Any I/O, computation, or IPC on it causes jank or ANRs.
- **Measure before optimizing.** Gut feelings are wrong. Profile first.
- **Understand the Android lifecycle.** Most performance bugs come from misusing Activity/Fragment/ViewModel lifecycles.
- **Release builds behave differently.** Always validate performance on a release build with minification enabled.
- **Real devices matter.** Emulators don't replicate thermal throttling, low-RAM behavior, or GPU differences.

---

## Common Entry Points & Triage

### "My app has ANRs in Play Console"
→ Read `references/anr.md`  
Key questions: What's the ANR type? (Input dispatch timeout / Service timeout / Broadcast timeout). Load the ANR traces from Play Console and look for the main thread stack.

### "My app is crashing"
→ Read `references/crashes.md`  
Key questions: Is it OOM, a NullPointer, or another exception? Is it reproducible? Is there a stack trace?

### "Cold start is too slow"
→ Read `references/startup.md`  
Key questions: What is the current TTID? Are you using Jetpack App Startup? Is Application.onCreate() doing heavy work?

### "My UI is janky / laggy"
→ Read `references/ui-rendering.md`  
Key questions: Is it in a list (RecyclerView)? Is it on scroll? Is GPU overdraw visible? What does the frame timeline in Android Studio show?

### "My app uses too much memory / has leaks"
→ Read `references/memory.md`  
Key questions: Is LeakCanary showing leaks? Is it OOM on low-RAM devices? Are you loading large Bitmaps?

### "Battery drain complaints"
→ Read `references/battery.md`  
Key questions: Is the app running background services? Using wakelocks? Frequent network polling?

---

## Baseline: Always Recommend These

Regardless of the specific issue, the following should always be in place:

```kotlin
// 1. StrictMode in debug builds - catches main thread violations early
if (BuildConfig.DEBUG) {
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectAll()
            .penaltyLog()
            .build()
    )
    StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .detectActivityLeaks()
            .penaltyLog()
            .build()
    )
}

// 2. Use Baseline Profiles (in release) for faster startup JIT/AOT
// See references/build-size.md

// 3. Use Android Vitals in Play Console for real-world data
// ANR rate, crash rate, slow start rate, slow frames
```

---

## Metrics Reference (know your targets)

| Metric | Good | Needs Work | Tool to Measure |
|---|---|---|---|
| Cold start TTID | < 1s | > 2s | Logcat `ActivityTaskManager`, Macrobenchmark |
| Warm start TTID | < 500ms | > 1.5s | Macrobenchmark |
| Hot start | < 200ms | > 500ms | Macrobenchmark |
| Frame render time | < 16ms | > 16ms (jank) | Android Studio Profiler, Perfetto |
| ANR rate | < 0.47% | > 1% (bad rating) | Play Console |
| Crash rate | < 1.09% | > 2% (bad rating) | Play Console / Firebase Crashlytics |
| Memory (mid-range) | < 200MB | > 300MB | Android Studio Memory Profiler |
| APK / AAB size | < 10MB | > 50MB (w/o reason) | Bundletool, Play Console |