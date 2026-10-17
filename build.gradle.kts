import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "pl.mdreview"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2025.1.3")
        // Optional dependency: recognizes the Markdown file type, see markdown-review-withMarkdown.xml.
        bundledPlugin("org.intellij.plugins.markdown")
        testFramework(TestFrameworkType.Platform)
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
    // BasePlatformTestCase integration tests are JUnit 3/4 style; the vintage engine runs them on the JUnit Platform.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.1")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }
    }
    // Secrets come from the environment (GitHub Actions secrets in the release workflow), never from the repository.
    // Signing runs only when the certificate chain and the private key are set; see docs/RELEASING.md.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A pre-release version such as 0.2.0-beta or 0.2.0-beta.2 goes to the "beta" channel, a plain version to the
        // default (stable) one.
        channels = providers.gradleProperty("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
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
