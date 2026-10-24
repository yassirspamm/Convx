# ANR (Application Not Responding) — Deep Reference

## What is an ANR?

The system triggers an ANR when:
- **Input dispatch timeout**: The main thread doesn't respond to user input within **5 seconds**
- **Service timeout**: A foreground service doesn't complete `onStartCommand()` within **20s**, background within **200s**
- **Broadcast timeout**: `BroadcastReceiver.onReceive()` doesn't return within **10s** (foreground) / **60s** (background)
- **Content Provider timeout**: A content provider doesn't respond within **10s**

---

## Diagnosing ANRs

### Step 1: Get the ANR trace

**Play Console → Android Vitals → ANRs & crashes**  
Download the ANR report. It contains a thread dump (`traces.txt` / `anr_*.txt`).

**Locally (debug):** ANR traces are written to `/data/anr/traces.txt` on the device.

```bash
adb pull /data/anr/traces.txt
```

### Step 2: Read the main thread stack

The main thread will be labeled `main` and typically show one of these patterns:

```
# PATTERN 1: Waiting on a lock (deadlock or contention)
"main" prio=5 tid=1 Blocked
  | waiting to lock <0x12345> held by thread 12

# PATTERN 2: Blocked on I/O
"main" prio=5 tid=1 Waiting
  at java.io.FileInputStream.read(Native Method)

# PATTERN 3: Blocked on Binder IPC (SharedPreferences, ContentProvider)
"main" prio=5 tid=1 Waiting
  at com.android.internal.os.BinderInternal.nativePollOnce(Native Method)
  at android.os.MessageQueue.nativePollOnce(MessageQueue.java)
  ... long wait time indicates blocked IPC

# PATTERN 4: Heavy computation on main thread
"main" prio=5 tid=1 Runnable
  at your.package.SomeClass.heavyOperation(SomeClass.kt:45)
```

---

## Root Causes & Fixes

### 1. I/O on the Main Thread

**Cause:** File reads, SharedPreferences commits, database queries on UI thread.

```kotlin
// ❌ BAD — blocks main thread
val prefs = getSharedPreferences("config", MODE_PRIVATE)
prefs.edit().putString("key", "value").commit() // commit() is synchronous!

// ✅ GOOD — apply() is async
prefs.edit().putString("key", "value").apply()

// ❌ BAD — reading file on main thread
val data = File(filesDir, "data.json").readText()

// ✅ GOOD — use coroutines
viewModelScope.launch(Dispatchers.IO) {
    val data = File(filesDir, "data.json").readText()
    withContext(Dispatchers.Main) { updateUI(data) }
}
```

### 2. Lock Contention / Synchronized Blocks

**Cause:** Background thread holds a lock that the main thread needs.

```kotlin
// ❌ BAD — synchronized cache accessed from multiple threads including main
object Cache {
    @Synchronized fun get(key: String): String? = map[key]
    @Synchronized fun put(key: String, value: String) = map.put(key, value)
}

// ✅ BETTER — use ConcurrentHashMap or confine mutations to one thread
val cache = ConcurrentHashMap<String, String>()

// ✅ BEST — use a dedicated thread/coroutine scope for cache mutations
private val cacheScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
```

### 3. Slow BroadcastReceiver

```kotlin
// ❌ BAD — doing work in onReceive on main thread
class MyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        doHeavyDatabaseWork() // will cause ANR!
    }
}

// ✅ GOOD — use goAsync() to extend deadline, or launch a coroutine
class MyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                doHeavyDatabaseWork()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
```

### 4. Slow Service Startup

```kotlin
// ❌ BAD — I/O in onStartCommand
class MyService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        processLargeFile() // blocks, can ANR!
        return START_NOT_STICKY
    }
}

// ✅ GOOD — use WorkManager or offload to background thread
class MyService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            processLargeFile()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
```

### 5. Slow `Application.onCreate()` / `ContentProvider.onCreate()`

```kotlin
// ❌ BAD — initializing SDKs synchronously
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HeavySDK.initialize(this)   // 200ms
        AnotherSDK.initialize(this) // 150ms
        // total: 350ms+ added to cold start AND can cause ANR on slow devices
    }
}

// ✅ GOOD — defer non-critical inits
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Only truly critical init here
        CrashReporter.initialize(this)
        
        // Defer everything else
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                CoroutineScope(Dispatchers.Default).launch {
                    HeavySDK.initialize(this@MyApp)
                }
            }
        })
    }
}
```

### 6. Binder IPC Saturation

ContentProviders, system services, and inter-process communication go through Binder, which has a thread pool limit (16 threads by default). If all threads are in use, Binder calls block.

```kotlin
// Use caching to reduce repeated Binder calls
class LocationHelper(private val context: Context) {
    // Cache the package manager result — don't call on every frame
    private val hasGpsFeature by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
    }
}
```

---

## Prevention Checklist

- [ ] **StrictMode** enabled in debug builds (see SKILL.md baseline section)
- [ ] **No `.commit()`** on SharedPreferences — always use `.apply()`
- [ ] **No synchronous I/O** on the main thread
- [ ] **No `Thread.sleep()`** on the main thread
- [ ] **BroadcastReceiver** uses `goAsync()` for any non-trivial work
- [ ] **Application.onCreate()** does minimal work — defer SDK inits
- [ ] **Lock-free data structures** (ConcurrentHashMap) for shared state
- [ ] **No database queries** from the main thread (Room enforces this by default)
- [ ] Trace with **Perfetto** or **Android Studio CPU Profiler** to spot main thread bottlenecks

---

## Tools

| Tool | How to Use |
|---|---|
| Android Studio CPU Profiler | Run > Profile app > CPU > Record method trace |
| Perfetto | `adb shell perfetto ...` or perfetto.dev/ui |
| StrictMode | See SKILL.md baseline — catches issues in dev |
| Play Console ANR traces | Android Vitals → download traces |
| `adb shell am bug-report` | Full device bug report including ANR traces |