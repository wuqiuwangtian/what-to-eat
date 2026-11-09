plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "org.whattoeat.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.whattoeat.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.0-dev.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["main"].assets.srcDir("../legal")
    sourceSets["androidTest"].assets.srcDir("schemas")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = false }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core"))
    "benchmarkImplementation"("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.03.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// 分别解析开发版和发行版，避免把调试工具算作正式随包组件。
listOf("debug", "release").forEach { variant ->
    tasks.register("${variant}RuntimeInventory") {
        val output = layout.buildDirectory.file("reports/$variant-runtime-artifacts.tsv")
        outputs.file(output)
        outputs.upToDateWhen { false }
        doLast {
            val artifacts = configurations.getByName("${variant}RuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            output.get().asFile.apply {
                parentFile.mkdirs()
                writeText(artifacts.sortedBy { it.moduleVersion.id.toString() }.joinToString("\n") {
                    "${it.moduleVersion.id}\t${it.file.absolutePath}"
                } + "\n")
            }
        }
    }
}
tasks.register("runtimeInventory") { dependsOn("debugRuntimeInventory") }
