import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.s9encryptor.strongtoastextended"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.s9encryptor.strongtoastextended"
        minSdk = 26
        targetSdk = 28
        versionCode = 101
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        // Directly installed legacy LSPosed modules retain their existing target behavior.
        warning += "ExpiredTargetSdkVersion"
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The bootstrap client is compiled on the build machine and ships inside this APK.
// Android devices do not need Termux, javac, D8, or a separate helper installation.
val bootstrapClasses = layout.buildDirectory.dir("generated/nativeBootstrap/classes")
val bootstrapDexDirectory = layout.buildDirectory.dir("generated/nativeBootstrap/dex")
val bootstrapAssets = layout.buildDirectory.dir("generated/nativeBootstrap/assets")

val compileNativeBootstrap = tasks.register<JavaCompile>("compileNativeBootstrap") {
    group = "build"
    description = "Compile the framework-only Java root bootstrap client"
    source(rootProject.fileTree("native-helper/src/main/java") { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(bootstrapClasses)
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
    options.bootstrapClasspath = files(provider { android.bootClasspath })
}

val dexNativeBootstrap = tasks.register<JavaExec>("dexNativeBootstrap") {
    group = "build"
    description = "Convert every bootstrap class into an API 26 compatible DEX"
    dependsOn(compileNativeBootstrap)
    inputs.dir(bootstrapClasses)
    outputs.dir(bootstrapDexDirectory)
    classpath = files(provider {
        File(android.sdkDirectory, "build-tools/${android.buildToolsVersion}/lib/d8.jar")
    })
    mainClass.set("com.android.tools.r8.D8")
    doFirst {
        val destination = bootstrapDexDirectory.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()
        val classes = bootstrapClasses.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.absolutePath }.sorted().toList()
        check(classes.isNotEmpty()) { "Native bootstrap Java sources produced no classes" }
        setArgs(listOf(
            "--min-api", "26",
            "--lib", android.bootClasspath.first().absolutePath,
            "--output", destination.absolutePath
        ) + classes)
    }
}

val generateNativeBootstrapAssets = tasks.register("generateNativeBootstrapAssets") {
    group = "build"
    description = "Package the root bootstrap DEX and its SHA-256 integrity digest"
    dependsOn(dexNativeBootstrap)
    inputs.file(bootstrapDexDirectory.map { it.file("classes.dex") })
    outputs.dir(bootstrapAssets)
    doLast {
        val source = bootstrapDexDirectory.get().file("classes.dex").asFile
        check(source.isFile && source.length() > 0L) { "Bootstrap DEX was not generated" }
        val destination = bootstrapAssets.get().asFile
        destination.mkdirs()
        val bytes = source.readBytes()
        File(destination, "strongtoast-bootstrap.dex").writeBytes(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        File(destination, "strongtoast-bootstrap.sha256").writeText("$digest\n", Charsets.US_ASCII)
    }
}

android.sourceSets.getByName("main").assets.srcDir(bootstrapAssets.get().asFile)
tasks.named("preBuild").configure { dependsOn(generateNativeBootstrapAssets) }

dependencies {
    compileOnly("de.robv.android.xposed:api:82")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("de.robv.android.xposed:api:82")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
