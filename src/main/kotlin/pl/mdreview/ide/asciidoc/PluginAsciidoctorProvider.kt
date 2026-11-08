package pl.mdreview.ide.asciidoc

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import org.asciidoctor.Asciidoctor
import pl.mdreview.engine.asciidoc.AsciidoctorProvider
import pl.mdreview.ide.AsciiDocSupport
import pl.mdreview.render.CoreMarkdownDiffRenderer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AsciidoctorJ from the JetBrains AsciiDoc plugin (ADR 0003).
 *
 * Declared in `markdown-review-withAsciiDoc.xml`, so this class and the AsciidoctorJ classes it imports load only
 * while that plugin is installed and enabled. One instance is created lazily on the first AsciiDoc diff; Asciidoctor
 * is thread-safe (P4.1), so all renders share it. [dispose] stops the Ruby runtime when the AsciiDoc plugin unloads.
 */
class PluginAsciidoctorProvider : AsciidoctorProvider, AsciiDocSupport.Runtime, Disposable {

    private val lock = Any()

    @Volatile
    private var instance: Asciidoctor? = null

    @Volatile
    private var preparing = false

    /** Renders waiting for the first Asciidoctor start. */
    private val readyCallbacks = CopyOnWriteArrayList<() -> Unit>()

    override fun asciidoctor(): Asciidoctor = instance ?: synchronized(lock) {
        instance ?: create().also { instance = it }
    }

    private fun create(): Asciidoctor {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        // JRuby resolves its gems through the thread context class loader; point it at the AsciiDoc plugin's classes.
        thread.contextClassLoader = Asciidoctor::class.java.classLoader
        try {
            return Asciidoctor.Factory.create()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    override fun isReady(): Boolean = instance != null

    override fun prepare(onReady: () -> Unit) {
        if (instance != null) {
            invokeLater(onReady)
            return
        }
        readyCallbacks += onReady
        synchronized(lock) {
            if (preparing) return
            preparing = true
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                asciidoctor()
            } catch (t: Throwable) {
                logger<PluginAsciidoctorProvider>().error("Could not start Asciidoctor", t)
                AsciiDocSupport.uninstall()
                CoreMarkdownDiffRenderer.uninstallAsciidoctorProvider()
            } finally {
                synchronized(lock) { preparing = false }
                val pending = readyCallbacks.toList()
                readyCallbacks.clear()
                pending.forEach(::invokeLater)
            }
        }
    }

    private fun invokeLater(callback: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(callback)
    }

    override fun dispose() {
        val current = instance
        instance = null
        AsciiDocSupport.uninstall()
        CoreMarkdownDiffRenderer.uninstallAsciidoctorProvider()
        if (current != null) {
            // Stop the Ruby runtime; the class loader stays reachable on JDK 21 (known limitation, ADR 0003).
            runCatching { current.shutdown() }
            runCatching { current.close() }
        }
    }
}
