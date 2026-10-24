# Profiling Tools — Deep Reference

## Android Studio Profiler

The primary tool for profiling CPU, memory, network, and energy.

### CPU Profiler

```
Profile → Select process → CPU tab

Recording modes:
- System Trace (Perfetto): Best for finding jank — shows all threads, frame timing
- Java/Kotlin Method Trace: Detailed call hierarchy (higher overhead)
- Callstack Sample: Low overhead CPU sampling
```

**Key things to look for:**
- `Choreographer#doFrame` — should complete in < 16ms
- `measure`, `layout`, `draw` on main thread — identify layout bottlenecks
- `suspend` coroutine resumes on wrong thread
- Gaps between frames (dropped frames)

### Memory Profiler

```
Profile → Memory tab

Actions:
- Heap Dump: Snapshot of all live objects — look for unexpected Activity/Fragment instances
- Record allocations: Find hot allocation paths causing GC pressure
```

**Key things to look for:**
- Multiple Activity instances (leak)
- Large Bitmap objects
- Growing heap that never shrinks (leak)
- Frequent GC events (many short-lived objects)

### Network Profiler

```
Profile → Network tab
Shows: request timeline, payload sizes, response times
```

---

## Custom Tracing (Perfetto / Systrace)

Add trace sections to your code to see them in Perfetto.

```kotlin
// Kotlin / Java tracing
import android.os.Trace

fun loadProducts() {
    Trace.beginSection("loadProducts")
    try {
        // your code
    } finally {
        Trace.endSection()
    }
}

// Coroutine-friendly tracing
suspend fun loadProductsSuspend() = withContext(Dispatchers.IO) {
    Trace.beginSection("loadProducts")
    try {
        repository.getProducts()
    } finally {
        Trace.endSection()
    }
}

// Jetpack Tracing library (recommended)
implementation("androidx.tracing:tracing-ktx:1.2.0")

fun loadProducts() {
    trace("loadProducts") {
        // code
    }
}
```

### Record Perfetto Trace

```bash
# Quick recording via adb
adb shell perfetto \
  -c - --txt \
  -o /data/misc/perfetto-traces/trace.perfetto-trace \
<<EOF
buffers: { size_kb: 63488 fill_policy: RING_BUFFER }
data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "am"
      atrace_categories: "wm"
      atrace_apps: "com.example.myapp"
    }
  }
}
duration_ms: 10000
EOF

adb pull /data/misc/perfetto-traces/trace.perfetto-trace ~/Desktop/

# Open at https://ui.perfetto.dev
```

**Key Perfetto tracks to examine:**
- **Frame timeline**: Shows each frame and whether it hit deadline
- **Main thread**: Look for long-running tasks during frames
- **Binder transactions**: IPC delays
- **Your custom trace sections**: `trace("mySection")` blocks

---

## Macrobenchmark

For measuring startup, scrolling jank, and feature performance in CI.

```kotlin
// :benchmark/build.gradle.kts
plugins {
    id("androidx.benchmark")
    id("com.android.test")
}
android {
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }
}
dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.4")
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// Test
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()
    
    @Test
    fun scrollProductList() = benchmarkRule.measureRepeated(
        packageName = "com.example.myapp",
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD
    ) {
        startActivityAndWait()
        val list = device.findObject(By.res("product_list"))
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN)
        waitForIdle()
    }
}

// Run:
// ./gradlew :benchmark:connectedReleaseAndroidTest -P android.testInstrumentationRunnerArguments.class=com.example.benchmark.ScrollBenchmark
```

---

## Microbenchmark

For benchmarking individual functions.

```kotlin
// :benchmark/build.gradle.kts
plugins {
    id("androidx.benchmark")
    id("com.android.library")
}

// Test
@RunWith(AndroidJUnit4::class)
class JsonParsingBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()
    
    private val rawJson = """{"id":1,"name":"Product","price":9.99}"""
    
    @Test
    fun parseProduct() {
        benchmarkRule.measureRepeated {
            Json.decodeFromString<Product>(rawJson)
        }
    }
}
```

---

## App Startup Tracing

```kotlin
// Add to your Application.onCreate() and Activity.onCreate()
class MyApp : Application() {
    override fun onCreate() {
        // This section will appear in Perfetto as "MyApp#onCreate"
        trace("MyApp#onCreate") {
            super.onCreate()
            trace("initCrashlytics") { Firebase.initialize(this) }
            trace("initImageLoader") { setupImageLoader() }
        }
    }
}
```

---

## Useful ADB Commands

```bash
# Start app and measure launch time
adb shell am start-activity -W -n com.example.myapp/.MainActivity
# Output: ThisTime: 312ms TotalTime: 490ms WaitTime: 510ms

# Dump memory info
adb shell dumpsys meminfo com.example.myapp

# Frame stats (last 128 frames)
adb shell dumpsys gfxinfo com.example.myapp framestats
# Janky frames count, 99th percentile render time

# CPU stats
adb shell dumpsys cpuinfo com.example.myapp

# Battery stats
adb shell dumpsys batterystats com.example.myapp

# Simulate low memory
adb shell am send-trim-memory com.example.myapp RUNNING_CRITICAL

# Enable/disable GPU rendering
adb shell setprop debug.hwui.profile true
```

---

## Profiling Workflow

1. **Identify symptoms** — ANR? Slow startup? Jank? OOM?
2. **Add `trace()` calls** to suspected areas
3. **Record** with Android Studio Profiler or Perfetto
4. **Analyze** — find the root cause in the trace
5. **Fix** — apply appropriate optimization
6. **Measure** — Macrobenchmark before/after to confirm improvement
7. **Prevent regression** — add benchmark to CI

---

## Tools Summary

| Tool | Best For | How to Access |
|---|---|---|
| Android Studio CPU Profiler | Method traces, system traces | Run → Profile |
| Android Studio Memory Profiler | Heap dumps, leak detection | Run → Profile |
| Android Studio Network Inspector | HTTP traffic inspection | Run → Profile |
| Perfetto | Deep system tracing, frame analysis | ui.perfetto.dev |
| Macrobenchmark | CI performance regression detection | Gradle test module |
| Microbenchmark | Function-level benchmarking | Gradle test module |
| Battery Historian | Battery drain analysis | bathist.ef.lc |
| APK Analyzer | App size breakdown | Build → Analyze APK |
| LeakCanary | Memory leak detection | Debug dependency |
| StrictMode | Thread/VM violation detection | Code (see SKILL.md) |