# Memory Optimization — Deep Reference

## Memory Concepts

| Term | Meaning |
|---|---|
| **PSS** (Proportional Set Size) | Most accurate memory metric for Android — includes shared memory proportionally |
| **RSS** (Resident Set Size) | Physical RAM used — includes full shared libs |
| **Java Heap** | Objects created via `new` / Kotlin allocations |
| **Native Heap** | Allocated via `malloc` (NDK, some bitmaps on API 26+) |
| **GC** (Garbage Collection) | Reclaims unreachable objects — pauses can cause jank |

---

## Memory Limits by Device Class

| Class | Heap Limit | `ActivityManager.isLowRamDevice()` |
|---|---|---|
| Low RAM (< 1GB) | ~32–64MB | `true` |
| Mid-range (2–4GB) | ~128–256MB | `false` |
| High-end (8GB+) | ~512MB+ | `false` |

```kotlin
// Adjust behavior for low-RAM devices
val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
if (activityManager.isLowRamDevice) {
    // Reduce image cache size, disable animations, load less data
    imageLoader.memCache.maxSizeBytes = 10 * 1024 * 1024 // 10MB
}

// Get heap limit programmatically
val maxHeap = Runtime.getRuntime().maxMemory() // bytes
```

---

## Memory Leaks

### Install LeakCanary

```kotlin
// build.gradle.kts
debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")
// That's it — no initialization code needed in Application
```

LeakCanary automatically detects Activity, Fragment, View, ViewModel, and Service leaks.

### Common Leak Patterns

#### 1. Static Reference to Context

```kotlin
// ❌ BAD — Activity held in static field forever
companion object {
    var instance: MainActivity? = null // leaks Activity + its view hierarchy
}

// ✅ GOOD — use WeakReference if you must, or Application context
companion object {
    private var weakInstance: WeakReference<MainActivity>? = null
}
```

#### 2. Anonymous Inner Classes / Lambdas Capturing Context

```kotlin
// ❌ BAD — Handler captures Activity implicitly
class MyActivity : AppCompatActivity() {
    val handler = Handler(Looper.getMainLooper())
    
    fun delayedAction() {
        handler.postDelayed({
            doSomething() // 'this' (Activity) captured in lambda
        }, 5000)
    }
}

// ✅ GOOD — use WeakReference or lifecycle-aware coroutines
class MyActivity : AppCompatActivity() {
    fun delayedAction() {
        lifecycleScope.launch {
            delay(5000)
            doSomething() // coroutine is cancelled when Activity destroys
        }
    }
}
```

#### 3. Listeners Not Unregistered

```kotlin
// ❌ BAD — listener registered but never removed
class MyActivity : AppCompatActivity() {
    override fun onStart() {
        super.onStart()
        sensorManager.registerListener(this, sensor, SENSOR_DELAY_UI)
    }
    // Forgot onStop!
}

// ✅ GOOD — pair register/unregister
override fun onStart() {
    super.onStart()
    sensorManager.registerListener(this, sensor, SENSOR_DELAY_UI)
}
override fun onStop() {
    super.onStop()
    sensorManager.unregisterListener(this)
}
```

#### 4. ViewModel Holding View/Context Reference

```kotlin
// ❌ BAD — ViewModel outlives Activity, holding its context
class MyViewModel(private val context: Context) : ViewModel() // leaks Activity!

// ✅ GOOD — use AndroidViewModel for app context
class MyViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext // safe
}

// ✅ BETTER — use @ApplicationContext with Hilt
class MyViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel()

// ❌ BAD — ViewModel holding a View or Fragment
class MyViewModel : ViewModel() {
    var callback: ((String) -> Unit)? = null // if callback captures Fragment → leak!
}

// ✅ GOOD — use StateFlow / LiveData for communication
class MyViewModel : ViewModel() {
    val uiState = MutableStateFlow<UiState>(UiState.Loading)
}
```

#### 5. Fragment View Binding Leak

```kotlin
// ❌ BAD — binding holds view reference beyond onDestroyView
class MyFragment : Fragment(R.layout.fragment_my) {
    private lateinit var binding: FragmentMyBinding
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentMyBinding.bind(view)
    }
    // binding keeps entire view hierarchy in memory after fragment goes on backstack!
}

// ✅ GOOD — null the binding in onDestroyView
class MyFragment : Fragment(R.layout.fragment_my) {
    private var _binding: FragmentMyBinding? = null
    private val binding get() = _binding!!
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentMyBinding.bind(view)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
```

---

## Bitmap Memory

Bitmaps are the #1 source of OOM on Android.

```kotlin
// Bitmap size formula:
// bytes = width × height × bytes_per_pixel
// ARGB_8888 = 4 bytes/px
// A 4000×3000 photo = 4000 × 3000 × 4 = 48MB !!!

// ✅ Always use an image loading library
// Coil (recommended for Kotlin/Compose):
implementation("io.coil-kt:coil:2.6.0")

imageView.load("https://example.com/image.jpg") {
    size(300, 200) // downscale to display size
    memoryCachePolicy(CachePolicy.ENABLED)
    diskCachePolicy(CachePolicy.ENABLED)
}

// Coil in Compose
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data("https://example.com/image.jpg")
        .crossfade(true)
        .build(),
    contentDescription = "User avatar",
    modifier = Modifier.size(48.dp)
)
```

---

## Profiling Memory

### Android Studio Memory Profiler

1. **Profile** → Select process → **Memory** tab
2. Look for:
   - Constantly growing heap (leak)
   - Large heap dump spikes (large allocations)
   - Frequent GC events (excessive short-lived object creation)
3. **Heap Dump** → Filter by package → Look for retained Activity/Fragment instances

### `adb dumpsys meminfo`

```bash
adb shell dumpsys meminfo com.example.myapp

# Key metrics:
# Java Heap: Used/Max (should not be near max)
# Native Heap: Used (bitmaps on API 26+)
# TOTAL PSS: overall memory footprint
```

### Allocation Tracking

```kotlin
// Add tracing to find hot allocation paths
fun processItems(items: List<Item>) {
    Trace.beginSection("processItems")
    items.forEach { item ->
        // Process each item — look for allocations in profiler
        transformItem(item)
    }
    Trace.endSection()
}
```

---

## Memory Management Best Practices

```kotlin
// 1. Use object pools for frequently allocated objects
class ParticleSystem {
    private val pool = Pools.SynchronizedPool<Particle>(50)
    
    fun acquireParticle(): Particle = pool.acquire() ?: Particle()
    fun releaseParticle(particle: Particle) { pool.release(particle) }
}

// 2. Respond to memory pressure
class MyApplication : Application(), ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // Free all non-essential memory
                imageCache.clear()
                dataCache.evictAll()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // Free some memory
                imageCache.trimToSize(imageCache.size() / 2)
            }
        }
    }
}

// 3. Avoid autoboxing in hot paths
// ❌ BAD — autoboxing in loop
val map = HashMap<Int, Int>() // boxes every int
for (i in 0..10000) map[i] = i * 2

// ✅ GOOD — use SparseArray for int keys
val sparseArray = SparseIntArray()
for (i in 0..10000) sparseArray[i] = i * 2
```

---

## Memory Checklist

- [ ] LeakCanary installed in debug builds
- [ ] All Fragment bindings nulled in `onDestroyView()`
- [ ] No Activity/Fragment/View context stored in singletons or ViewModels
- [ ] Image loading via Coil/Glide — never raw BitmapFactory at full resolution
- [ ] `onTrimMemory()` implemented in Application to free caches
- [ ] Large data loaded with Paging 3 — never fully in memory
- [ ] `SparseArray`/`SparseIntArray` for int-keyed maps
- [ ] Listeners unregistered in lifecycle callbacks
- [ ] No anonymous inner classes capturing Activity