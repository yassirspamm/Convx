# Build & Code Size — Deep Reference

## R8 / ProGuard (Mandatory for Release)

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        release {
            isMinifyEnabled = true      // R8 shrinking + obfuscation
            isShrinkResources = true    // Remove unused resources
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false // Keep debug fast
        }
    }
}
```

### Essential ProGuard Rules

```proguard
# proguard-rules.pro

# Keep data classes used with Gson/Moshi/kotlinx.serialization
-keep class com.example.myapp.model.** { *; }
-keepclassmembers class com.example.myapp.model.** { *; }

# Keep Parcelables
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep Enum names (if used in serialization)
-keepclassmembers enum * { *; }

# Keep native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
```

### Upload Mapping File to Play Console

```kotlin
// build.gradle.kts — auto-upload mapping file
plugins {
    id("com.google.firebase.crashlytics")
}

// This automatically uploads proguard mapping on release builds
// Enables deobfuscated crash stacks in Firebase Crashlytics
```

---

## APK / AAB Size Reduction

### Analyze APK Size

```bash
# Android Studio: Build → Analyze APK
# Shows breakdown: dex, resources, assets, native libs

# Or use bundletool for AAB
bundletool build-apks --bundle=app.aab --output=app.apks
bundletool get-size total --apks=app.apks
```

### Strategies

```kotlin
// 1. App Bundle (AAB) instead of APK — Play Store delivers only needed resources
// ~20-40% size reduction automatically
plugins {
    id("com.android.application")
}
// bundle {} config in android {} block

// 2. WebP for images (30-40% smaller than PNG)
// In Android Studio: right-click res → Convert to WebP

// 3. Vector drawables instead of PNGs for icons
// <vector android:width="24dp" ...> — scales to any density

// 4. Split ABIs (only if not using AAB)
android {
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

// 5. Dynamic Feature Modules — defer download of rarely used features
// User downloads core app, features downloaded on demand
```

---

## Baseline Profiles

Baseline Profiles are compiled rules that tell ART which code to AOT-compile, improving:
- **Cold start speed** by 15-40%
- **Jank** in early user interactions
- **Feature launch speed**

```kotlin
// :benchmark/src/main/kotlin/BaselineProfileGenerator.kt
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.example.myapp",
        profileBlock = {
            pressHome()
            startActivityAndWait()
            
            // Trace critical user journeys
            device.findObject(By.res("product_list")).wait(Until.hasObject(By.text(".*")), 3000)
            device.findObject(By.text("First Product")).click()
            waitForIdle()
        }
    )
}

// app/build.gradle.kts
plugins {
    id("androidx.baselineprofile")
}
dependencies {
    baselineProfile(project(":benchmark"))
}
```

The generated `baseline-prof.txt` is included in your AAB automatically.

---

## Build Performance (Developer Velocity)

```kotlin
// gradle.properties
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true

// app/build.gradle.kts
android {
    // Only build the density you need locally
    productFlavors {
        // Use resource shrinking in debug too if working on resource usage
    }
    
    // Skip lint on debug builds
    lint {
        checkReleaseBuilds = false
    }
}
```

---

## Multidex

```kotlin
// Required if dex method count > 64K (most large apps)
android {
    defaultConfig {
        multiDexEnabled = true
    }
}
dependencies {
    implementation("androidx.multidex:multidex:2.0.1")
}

// Application.kt
class MyApp : MultiDexApplication() {
    // or call MultiDex.install(this) in attachBaseContext
}
```

---

## Code Size Checklist

- [ ] R8 minification enabled for release builds
- [ ] `isShrinkResources = true` in release build type
- [ ] Publishing as AAB (not APK) to Play Store
- [ ] ProGuard mapping file uploaded to Crashlytics for readable stack traces
- [ ] PNG images converted to WebP
- [ ] Icon assets as Vector Drawables (not PNG)
- [ ] APK Analyzer run to identify largest contributors
- [ ] Baseline Profiles generated and included for release builds
- [ ] Unused dependencies removed