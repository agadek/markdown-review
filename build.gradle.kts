plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "pl.mdreview"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2025.1.3")
    }
    val commonmark = "0.30.1"
    implementation("org.commonmark:commonmark:$commonmark")
    implementation("org.commonmark:commonmark-ext-gfm-tables:$commonmark")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:$commonmark")
    implementation("org.commonmark:commonmark-ext-task-list-items:$commonmark")
    implementation("org.commonmark:commonmark-ext-autolink:$commonmark")
    implementation("org.commonmark:commonmark-ext-footnotes:$commonmark")
    implementation("org.commonmark:commonmark-ext-gfm-alerts:$commonmark")
    // Block and word diffs in the engine (pl.mdreview.engine), usable without the IntelliJ Platform.
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")
    testImplementation(kotlin("test"))
    // Gradle 9 no longer puts the JUnit Platform launcher on the test classpath by itself.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
    // The IntelliJ test environment initializer references JUnit 4 classes even for JUnit 5 tests.
    testRuntimeOnly("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }
    }
    pluginVerification {
        ides {
            create("IC", "2025.1.3")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
