plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

android {
    namespace = "blbl.cat3399"
    compileSdk = 36

    fun propOrEnv(name: String): String? {
        val fromProp = project.findProperty(name) as String?
        if (!fromProp.isNullOrBlank()) return fromProp
        val fromEnv = System.getenv(name)
        if (!fromEnv.isNullOrBlank()) return fromEnv
        return null
    }

    defaultConfig {
        applicationId = "blbl.cat3399"
        minSdk = 19
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = project.findProperty("versionName") as String? ?: "0.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        multiDexEnabled = true
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.keystore")
            storePassword = propOrEnv("RELEASE_STORE_PASSWORD")
            keyAlias = propOrEnv("RELEASE_KEY_ALIAS")
            keyPassword = propOrEnv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    flavorDimensions += "api"
    productFlavors {
        create("kitkat") {
            dimension = "api"
            minSdk = 19
            applicationIdSuffix = ".kitkat"
            versionNameSuffix = "-kitkat"
        }
        create("lollipopplus") {
            dimension = "api"
            minSdk = 21
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
        jniLibs {
            // libijkplayer.so is excluded by default (downloaded on demand).
            // The kitkat flavor will override this via androidComponents.onVariants.
            excludes += setOf("**/libijkplayer.so")
        }
    }

    // Configure per-variant packaging for kitkat flavor
    androidComponents {
        onVariants { variant ->
            if (variant.flavorName == "kitkat") {
                // API 19 devices are 32-bit ARM only; include libijkplayer.so but strip other ABIs
                variant.packaging.jniLibs.excludes.set(setOf(
                    "**/arm64-v8a/**",
                    "**/x86/**",
                    "**/x86_64/**",
                ))
            }
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(files("libs/ijkplayer-cmake-release.aar"))

    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("org.brotli:dec:0.1.2")
    "kitkatImplementation"("org.conscrypt:conscrypt-android:2.5.2")

    "kitkatImplementation"("com.squareup.okhttp3:okhttp:3.12.13")
    "lollipopplusImplementation"("com.squareup.okhttp3:okhttp:4.12.0")

    "kitkatImplementation"("androidx.media3:media3-exoplayer:1.4.1")
    "kitkatImplementation"("androidx.media3:media3-exoplayer-hls:1.4.1")
    "kitkatImplementation"("androidx.media3:media3-ui:1.4.1")
    "kitkatImplementation"("androidx.media3:media3-datasource:1.4.1")

    "lollipopplusImplementation"("androidx.media3:media3-exoplayer:1.8.0")
    "lollipopplusImplementation"("androidx.media3:media3-exoplayer-hls:1.8.0")
    "lollipopplusImplementation"("androidx.media3:media3-ui:1.8.0")
    "lollipopplusImplementation"("androidx.media3:media3-datasource:1.8.0")
    "lollipopplusImplementation"("androidx.media3:media3-datasource-okhttp:1.8.0")

    implementation("com.google.protobuf:protobuf-javalite:3.25.3")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// Enforce theme-token usage in layouts so adding new theme presets doesn't silently break contrast.
val checkThemeTokens =
    tasks.register("checkThemeTokens") {
        group = "verification"
        description = "Fails if layouts reference fixed palette colors instead of theme attributes."

        doLast {
            val resDir = file("src/main/res")
            val layoutDirs =
                resDir
                    .listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("layout") }
                    .orEmpty()

            fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

            // Match whole resource refs (word boundary) to avoid false positives like
            // `@color/blbl_text_on_media` or `@drawable/blbl_focus_bg_round_danger`.
            fun containsWholeToken(line: String, token: String): Boolean {
                var fromIndex = 0
                while (true) {
                    val idx = line.indexOf(token, startIndex = fromIndex)
                    if (idx < 0) return false
                    val before = line.getOrNull(idx - 1)
                    val after = line.getOrNull(idx + token.length)
                    val beforeOk = before == null || !isWordChar(before)
                    val afterOk = after == null || !isWordChar(after)
                    if (beforeOk && afterOk) return true
                    fromIndex = idx + token.length
                }
            }

            val forbidden =
                listOf(
                    "@color/blbl_bg",
                    "@color/blbl_surface",
                    "@color/blbl_text",
                    "@color/blbl_text_secondary",
                    "@color/blbl_focus_stroke",
                    "@drawable/blbl_focus_bg_round",
                )

            val violations = mutableListOf<String>()
            for (dir in layoutDirs) {
                dir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
                    .forEach { f ->
                        val relPath = f.relativeTo(projectDir).invariantSeparatorsPath
                        val lines = f.readLines(Charsets.UTF_8)
                        for ((index, line) in lines.withIndex()) {
                            for (token in forbidden) {
                                if (containsWholeToken(line, token)) {
                                    violations.add("$relPath:${index + 1}: $token")
                                }
                            }
                        }
                    }
            }

            if (violations.isNotEmpty()) {
                val msg =
                    buildString {
                        appendLine("Theme token check failed: layouts must use theme attributes, not fixed palette colors.")
                        appendLine(
                            "Use ?attr/colorOnSurface, ?android:attr/textColorSecondary, ?attr/colorBackground, " +
                                "?attr/colorSurface, ?attr/blblOnPageBackdrop, ?attr/blblFocusBgRound, " +
                                "?attr/blblFocusStrokeColor, etc.",
                        )
                        appendLine("Violations:")
                        violations.forEach { appendLine("  $it") }
                    }
                throw GradleException(msg)
            }
        }
    }

tasks.named("preBuild").configure {
    dependsOn(checkThemeTokens)
}
