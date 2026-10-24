# App Startup Optimization — Deep Reference

## Startup Types

| Type | When | What's Recreated | Typical Target |
|---|---|---|---|
| **Cold Start** | App not in memory at all | Process + Application + Activity | < 1000ms TTID |
| **Warm Start** | App in memory, Activity killed | Activity only | < 500ms TTID |
| **Hot Start** | App in memory, Activity in backstack | Activity brought to foreground | < 200ms |

**TTID** = Time To Initial Display (first frame drawn)  
**TTFD** = Time To Full Display (content fully loaded, requires `reportFullyDrawn()`)

---

## Measuring Startup Time

### 1. Logcat (simplest)
```bash
adb logcat | grep "ActivityTaskManager"
# Output: "Displayed com.example/.MainActivity: +1s234ms"
```

### 2. `reportFullyDrawn()` — mark when content is truly ready
```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // After data loads and UI is meaningful:
        lifecycleScope.launch {
            viewModel.data.collect { data ->
                adapter.submitList(data)
                reportFullyDrawn() // signals true readiness
            }
        }
    }
}
```

### 3. Macrobenchmark (recommended for CI)
```kotlin
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = "com.example.myapp",
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD
    ) {
        pressHome()
        startActivityAndWait()
    }
}
```

---

## Cold Start Timeline

```
App Process Created
        │
        ▼
Application.onCreate()        ← BIGGEST OPPORTUNITY
        │
        ▼
ContentProvider.onCreate()    ← Often hidden cost (libraries inject here!)
        │
        ▼
Activity.onCreate()
        │
        ▼
Activity.onStart() / onResume()
        │
        ▼
First frame drawn (TTID)
        │
        ▼
Data loaded / reportFullyDrawn() (TTFD)
```

---

## Optimization Techniques

### 1. Audit `Application.onCreate()` — Most Impactful

```kotlin
// Step 1: Add tracing to find slow inits
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        trace("MyApp#onCreate") {
            trace("Firebase init") { FirebaseApp.initializeApp(this) }
            trace("Analytics init") { Analytics.initialize(this) }
            trace("ImageLoader init") { ImageLoader.setup(this) }
        }
    }
}

// Inspect in Android Studio Profiler → CPU → System Trace
```

```kotlin
// Step 2: Defer non-critical SDKs
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // ✅ Critical: crash reporter only
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
        
        // ✅ Defer analytics until first screen
        AppInitializer.getInstance(this)
            .initializeComponent(AnalyticsInitializer::class.java)
            // Use Jetpack App Startup with lazy=true
    }
}
```

### 2. Jetpack App Startup — Organize & Parallelize Init

```kotlin
// Instead of ad-hoc init in Application, use App Startup
class AnalyticsInitializer : Initializer<AnalyticsClient> {
    override fun create(context: Context): AnalyticsClient {
        return AnalyticsClient.initialize(context)
    }
    override fun dependencies() = emptyList<Class<out Initializer<*>>>()
}

// In AndroidManifest.xml
// <provider android:name="androidx.startup.InitializationProvider"
//           android:authorities="${applicationId}.androidx-startup">
//   <meta-data android:name="com.example.AnalyticsInitializer"
//              android:value="androidx.startup" />
// </provider>
```

### 3. Eliminate Hidden ContentProvider Inits

Many libraries register a `ContentProvider` to auto-initialize (Firebase, WorkManager, etc.).

```bash
# Find all ContentProviders in your merged manifest
./gradlew :app:processDebugMainManifest
cat app/build/intermediates/merged_manifests/debug/AndroidManifest.xml | grep "provider"
```

To **disable** an unwanted auto-init:
```xml
<!-- AndroidManifest.xml -->
<provider
    android:name="com.example.SomeLibrary$InitProvider"
    android:authorities="${applicationId}.some-lib-init"
    tools:node="remove" />
```

### 4. Splash Screen API (Android 12+)

```kotlin
// In themes.xml (v31+)
// <style name="Theme.App.Starting" parent="Theme.SplashScreen">
//   <item name="windowSplashScreenBackground">@color/brand_color</item>
//   <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher</item>
//   <item name="postSplashScreenTheme">@style/Theme.App</item>
// </style>

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Keep splash visible until data is ready
        splashScreen.setKeepOnScreenCondition {
            !viewModel.isReady.value
        }
    }
}
```

### 5. Baseline Profiles — AOT-Compile Hot Paths

Baseline Profiles tell the ART runtime which code to AOT-compile, dramatically improving first-run speed.

```kotlin
// BaselineProfileGenerator.kt (in :benchmark module)
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.example.myapp"
    ) {
        pressHome()
        startActivityAndWait()
        // Navigate critical user journeys
        device.findObject(By.text("Products")).click()
        waitForIdle()
    }
}
```

```kotlin
// app/build.gradle.kts
plugins {
    id("androidx.baselineprofile")
}
dependencies {
    baselineProfile(project(":benchmark"))
}
```

### 6. Lazy Initialization Patterns

```kotlin
// ✅ Lazy singleton — initialized on first access, not at startup
class DatabaseProvider(private val context: Context) {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, "app.db").build()
    }
}

// ✅ ViewModel — only created when first needed
class MainViewModel : ViewModel() {
    val heavyData by lazy { HeavyDataProcessor.load() }
}

// ✅ Dagger/Hilt — use @Singleton with lazy injection
@Singleton
class AnalyticsRepository @Inject constructor(
    @ApplicationContext private val context: Context
)
```

### 7. View Inflation Optimization

```kotlin
// ✅ Use ViewStub for non-critical UI
<ViewStub
    android:id="@+id/stub_error_view"
    android:layout="@layout/error_view"
    android:inflatedId="@+id/error_view" />

// Inflate only when needed
val stub = findViewById<ViewStub>(R.id.stub_error_view)
stub.inflate() // only pays inflation cost when actually shown

// ✅ Use AsyncLayoutInflater for complex layouts
val inflater = AsyncLayoutInflater(this)
inflater.inflate(R.layout.complex_layout, null) { view, _, _ ->
    setContentView(view)
}
```

### 8. R8 / ProGuard — Reduce Dex Load Time

```kotlin
// build.gradle.kts
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

---

## Startup Anti-Patterns

| Anti-Pattern | Fix |
|---|---|
| Network call in `Application.onCreate()` | Defer to after first screen loads |
| Synchronous SharedPreferences read for config | Use DataStore with async reads |
| Loading all feature modules at startup | Use dynamic feature modules |
| Creating large ViewModels eagerly | Use `by viewModels()` with lazy factories |
| Decoding large Bitmaps in `onCreate()` | Load async with Coil/Glide |
| Room `.allowMainThreadQueries()` | Remove it — use async queries |
| Heavy DI graph constructed at startup | Use lazy bindings in Hilt/Dagger |