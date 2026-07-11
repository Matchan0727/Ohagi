val exposed_version: String by project
val logback_version: String by project
val jda_version: String by project
val jda_ktx_version: String by project

plugins {
    kotlin("jvm") version "2.3.21"
    id("io.ktor.plugin") version "3.4.3"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
}

group = "jp.simplespace"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

repositories {
    mavenCentral()
    maven { url = uri("https://m2.chew.pro/releases") }
}

dependencies {
    // Web系
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-thymeleaf")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("io.ktor:ktor-serialization-gson")
    // DB系
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("com.mysql:mysql-connector-j:9.5.0")
    // JDA系
    implementation("net.dv8tion:JDA:$jda_version")
    implementation("dev.arbjerg:lavaplayer:2.2.6")
    // Interface to use for libraries
    implementation("club.minnced:jdave-api:0.1.8")
    // Compiled natives for libdave for the specified platform
    implementation("club.minnced:jdave-native-linux-x86-64:0.1.8")
    implementation("club.minnced:jdave-native-linux-aarch64:0.1.8")
    implementation("club.minnced:jdave-native-win-x86-64:0.1.8")
    implementation("club.minnced:jdave-native-darwin:0.1.8")
    implementation("pw.chew:jda-chewtils:2.2.1")
    implementation("club.minnced:jda-ktx:$jda_ktx_version")
    // Google系
    implementation("com.google.api-client:google-api-client:2.0.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1")
    implementation("com.google.apis:google-api-services-calendar:v3-rev20220715-2.0.0")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.21")
    // その他
    implementation("org.openjdk.nashorn:nashorn-core:15.7")
    implementation("ch.qos.logback:logback-classic:${logback_version}")
    implementation("org.jsoup:jsoup:1.21.2")
}

kotlin {
    jvmToolchain(25)
}
