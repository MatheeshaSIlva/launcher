// The desktop half of the step-0 benchmarks (docs/BENCH_STEP0.md): the glass shaders through Skiko, the Skia that Compose
// Multiplatform 1.12.2 draws with (0.150.2 is what its ui-graphics pins), and LuaJ, Rembulan and kdl4j on HotSpot for
// reference. Built only with -Pbench. Run: ./gradlew -Pbench :bench-desktop:run --args="glass PHONE_DIR"
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
    }
}

sourceSets {
    main {
        kotlin.srcDirs("src", "../bench-shared/src", "../bench-shared/kdl", "../bench-shared/luaj", "../bench-shared/rembulan")
    }
}

dependencies {
    val skiko = "0.150.2"
    implementation("org.jetbrains.skiko:skiko-awt:$skiko")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:$skiko")
    implementation("com.github.kdl-org:kdl4j:v1.0.1")
    implementation("org.luaj:luaj-jse:3.0.1")
    implementation("com.jukusoft:rembulan-compiler:1.0.3")
    implementation("com.jukusoft:rembulan-stdlib:1.0.3")
}

application {
    mainClass.set("dev.launcher.bench.desktop.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
