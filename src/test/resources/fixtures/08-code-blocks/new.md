# Example

```kotlin
fun main() {
    val diff = MarkdownDiff.compute(old, new, ignoreWhitespace = true)
    diff.blocks.forEach { println(it.status) }
    println(diff.blocks.size)
}
```

Indented code block:

    ./gradlew runIde

```json
no language
```

Inline code: `FrameDiffTool`.
