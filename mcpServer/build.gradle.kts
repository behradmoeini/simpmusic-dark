// SimpMusic MCP server: plays YouTube Music from a chat client, with no SimpMusic app installed.
// It reuses core's YouTube Music client (search, radio, stream extraction) and hands the audio to
// mpv, which the user installs. Packaged as a zip with launch scripts (installDist / distZip).
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.simpmusic.mcp.MainKt")
    applicationName = "simpmusic-mcp"
}

dependencies {
    implementation(projects.kotlinYtmusicScraper)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.android.get()}")
}
