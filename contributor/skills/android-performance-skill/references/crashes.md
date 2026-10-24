# Crashes — Deep Reference

## Crash Types

| Type | Cause | Priority |
|---|---|---|
| `NullPointerException` | Null dereference | High — often preventable with Kotlin nullability |
| `OutOfMemoryError` | Heap exhaustion | High — memory management issue |
| `IllegalStateException` | Wrong lifecycle usage | High — lifecycle bug |
| `ClassCastException` | Type mismatch | Medium |
| `IndexOutOfBoundsException` | Array/list access | Medium |
| `NetworkOnMainThreadException` | Network on UI thread | Medium — StrictMode prevents |
| `WindowManager.BadTokenException` | Dialog after Activity destroyed | Medium — lifecycle |
| `TransactionTooLargeException` | Binder parcel > 1MB | Medium — data size |
| `StackOverflowError` | Infinite recursion | Low |
| Native crashes (`SIGSEGV`, `SIGABRT`) | Native code, JNI | High — hardest to debug |

---

## Setting Up Crash Reporting

```kotlin
// Firebase Crashlytics (recommended)
// build.gradle.kts
plugins {
    id("com.google.firebase.crashlytics")
}
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:32.0.0"))
    implementation("com.google.firebase:firebase-crashlytics-ktx")
}

// Log non-fatal exceptions
try {
    riskyOperation()
} catch (e: Exception) {
    Firebase.crashlytics.recordException(e)
}

// Add context to crashes
Firebase.crashlytics.setCustomKey("user_id", userId)
Firebase.crashlytics.log("User tapped checkout button")
```

---

## OutOfMemoryError (OOM)

### Diagnosing OOM

```bash
# Check heap stats on device
adb shell dumpsys meminfo com.example.myapp

# Get heap dump from Android Studio:
# Profiler → Memory → Heap dump button
```

### Common OOM Causes & Fixes

#### 1. Bitmap Loading

```kotlin
// ❌ BAD — loading full resolution bitmap
val bitmap = BitmapFactory.decodeFile(imagePath)
imageView.setImageBitmap(bitmap)

// ✅ GOOD — use Coil (or Glide) which handles sampling, caching, lifecycle
imageView.load(imagePath) {
    size(imageView.width, imageView.height)
    crossfade(true)
}

// ✅ If manual: sample down to target size
fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(path, options)
}
```

#### 2. Memory Leaks (see memory.md for full coverage)

```kotlin
// ❌ BAD — Activity context stored in singleton
object ImageCache {
    var context: Context? = null // holds Activity forever!
}

// ✅ GOOD — use application context
object ImageCache {
    lateinit var appContext: Context
    
    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
```

#### 3. Large Data in Memory

```kotlin
// ❌ BAD — loading entire dataset
val allUsers = db.userDao().getAllUsers() // 100k users in memory

// ✅ GOOD — use Paging 3
val userFlow = Pager(PagingConfig(pageSize = 20)) {
    db.userDao().getUsersPaged()
}.flow.cachedIn(viewModelScope)
```

---

## NullPointerException

Kotlin eliminates most NPEs, but they still occur via:

```kotlin
// 1. Java interop — Java APIs return nullable without annotation
val result: String? = javaApi.getValue() // could be null
result!!.length // ❌ BAD — crashes if null

result?.length ?: 0 // ✅ GOOD

// 2. lateinit var used before init
class MyFragment : Fragment() {
    private lateinit var binding: FragmentMyBinding
    
    override fun onDestroyView() {
        super.onDestroyView()
        binding = null // ❌ can't null a lateinit var like this
    }
    
    // ✅ Use nullable binding pattern
    private var _binding: FragmentMyBinding? = null
    private val binding get() = _binding!!
    
    override fun onCreateView(...): View {
        _binding = FragmentMyBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// 3. View lookups after onDestroyView
class MyFragment : Fragment() {
    // ❌ BAD — textView can be null after fragment detach
    private var textView: TextView? = null
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textView = view.findViewById(R.id.text)
    }
    
    fun updateText(msg: String) {
        textView?.text = msg // ✅ safe call
    }
}
```

---

## IllegalStateException (Lifecycle Crashes)

```kotlin
// ❌ BAD — committing fragment transaction after onSaveInstanceState
fun showDialog() {
    supportFragmentManager.beginTransaction()
        .add(MyDialog(), "dialog")
        .commit() // crashes if called after onSaveInstanceState
}

// ✅ GOOD
fun showDialog() {
    supportFragmentManager.beginTransaction()
        .add(MyDialog(), "dialog")
        .commitAllowingStateLoss() // or check lifecycle state
}

// ❌ BAD — accessing ViewModel after Activity is destroyed
viewModel.data.observe(this) { // 'this' must be a valid LifecycleOwner
    updateUI(it)
}

// ✅ GOOD — use viewLifecycleOwner in Fragments
viewModel.data.observe(viewLifecycleOwner) { updateUI(it) }
```

---

## TransactionTooLargeException

Binder has a 1MB limit per transaction. Intent extras and `onSaveInstanceState` bundles go through Binder.

```kotlin
// ❌ BAD — passing large objects via Intent
val intent = Intent(this, DetailActivity::class.java)
intent.putExtra("data", largeParcelableList) // can exceed 1MB

// ✅ GOOD — pass an ID, load data in destination
val intent = Intent(this, DetailActivity::class.java)
intent.putExtra("item_id", item.id)
// DetailActivity loads from Room/cache by ID

// ❌ BAD — saving large data in onSaveInstanceState
override fun onSaveInstanceState(outState: Bundle) {
    outState.putParcelableArrayList("items", hugeList)
}

// ✅ GOOD — use ViewModel to survive config changes (no Binder involved)
class MyViewModel : ViewModel() {
    val items = MutableStateFlow<List<Item>>(emptyList())
}
```

---

## Native Crashes (SIGSEGV, SIGABRT)

```bash
# Symbolicate native crash using ndk-stack
adb logcat | ndk-stack -sym app/build/intermediates/merged_native_libs/release/

# Or use Firebase Crashlytics native crash reporting
// build.gradle.kts
implementation("com.google.firebase:firebase-crashlytics-ndk")

android {
    buildTypes {
        release {
            ndk { debugSymbolLevel = "FULL" }
        }
    }
}
```

---

## Play Console Crash Triage Workflow

1. **Android Vitals → Crashes** — sort by "Impact" (affects most users)
2. **Filter by OS version / device** — is it device-specific?
3. **Read the stack trace** — identify the root exception, not wrapped ones
4. **Check "affected clusters"** — similar crashes grouped together
5. **Correlate with release dates** — did crash rate spike after a release?

```kotlin
// Add breadcrumbs to help reproduce
Firebase.crashlytics.log("Screen: ProductList")
Firebase.crashlytics.log("Action: tapped item ${item.id}")
Firebase.crashlytics.setCustomKey("cart_size", cart.size)
Firebase.crashlytics.setCustomKey("network_type", networkType)
```

---

## Crash Prevention Checklist

- [ ] Kotlin nullability used throughout — no `!!` unless absolutely justified
- [ ] Fragment view binding cleared in `onDestroyView()`
- [ ] `commitAllowingStateLoss()` for non-critical fragment transactions
- [ ] No large objects in Intents or `onSaveInstanceState` bundles — use ViewModel
- [ ] Coil/Glide for all image loading — never raw `BitmapFactory.decodeFile` at full res
- [ ] Firebase Crashlytics (or equivalent) integrated with custom keys
- [ ] LeakCanary in debug builds (see memory.md)
- [ ] ProGuard mapping files uploaded to Play Console for deobfuscated traces