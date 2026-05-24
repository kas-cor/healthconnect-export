plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
    id("jacoco")
}

android {
    namespace = "com.healthconnect.export"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.healthconnect.export"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    aaptOptions {
        noCompress += ".arsc"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("healthconnect-release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "healthconnect"
            keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystoreExists = rootProject.file("healthconnect-release.jks").exists()
            println("DEBUG SIGNING: keystore exists=$keystoreExists, KEYSTORE_PASSWORD set=${System.getenv("KEYSTORE_PASSWORD") != null}")
            signingConfig = if (keystoreExists) {
                signingConfigs.getByName("release")
            } else null
        }
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        // Ignore missing translation for default locale
        disable.add("MissingTranslation")
    }
}

kotlin {
    jvmToolchain(21)
}

// JaCoCo coverage configuration
jacoco {
    toolVersion = "0.8.11"
}

tasks.register("jacocoTestReport", JacocoReport::class) {
    dependsOn("testDebugUnitTest")

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }

    val fileFilter = listOf(
        "**/R.class",
        "**/R\$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*"
    )

    val debugTree = fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug") {
        exclude(fileFilter)
    }
    val kotlinDebugTree = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }

    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(files(debugTree, kotlinDebugTree))
    executionData.setFrom(fileTree(layout.buildDirectory.get()) {
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })
}

// Health Connect permissions property injection into merged manifest
tasks.matching { it.name == "processDebugMainManifest" }.configureEach {
    doLast {
        val manifestPath = "${layout.buildDirectory.get()}/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
        val manifestFile = file(manifestPath)
        if (manifestFile.exists()) {
            var content = manifestFile.readText()
            val propertyBlock = """        <property
            android:name="android.health.HealthConnectPermissions"
            android:value="@xml/health_connect_permissions" />"""
            if (!content.contains("HealthConnectPermissions")) {
                content = content.replace("</application>", "$propertyBlock\n    </application>")
                manifestFile.writeText(content)
                println("Added HealthConnectPermissions property to merged manifest")
            }
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Compose
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Google Sign-In & Drive API
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240123-2.0.0")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
