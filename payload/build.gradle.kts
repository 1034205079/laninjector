import java.util.Properties

plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

fun getSdkDir(): String {
    val localProps = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { localProps.load(it) }
    return localProps.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: throw GradleException("Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME env var.")
}

dependencies {
    compileOnly(files("${getSdkDir()}/platforms/android-36/android.jar"))
}

tasks.register("generatePayloadDex") {
    dependsOn("jar")

    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val outputDex = rootProject.file("app/src/main/assets/payload.dex")

    inputs.file(jarFile)
    outputs.file(outputDex)

    doLast {
        val sdk = getSdkDir()
        val buildToolsDir = file("$sdk/build-tools")
        val latestBuildTools = buildToolsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }
            ?: throw GradleException("No build-tools found in $sdk/build-tools")

        val d8 = file("${latestBuildTools}/d8.bat").takeIf { it.exists() }
            ?: file("${latestBuildTools}/d8").takeIf { it.exists() }
            ?: throw GradleException("d8 not found in ${latestBuildTools}")

        val tempDir = temporaryDir
        outputDex.parentFile.mkdirs()

        val process = ProcessBuilder(
            d8.absolutePath,
            "--min-api", "24",
            "--output", tempDir.absolutePath,
            jarFile.get().asFile.absolutePath
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("d8 failed (exit code $exitCode):\n$output")
        }

        file("${tempDir}/classes.dex").copyTo(outputDex, overwrite = true)
        logger.lifecycle("Generated payload.dex at ${outputDex.absolutePath}")
    }
}
