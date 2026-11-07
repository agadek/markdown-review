package pl.mdreview.ide

/**
 * Availability and startup of AsciiDoc rendering (NFR-11, ADR 0003).
 *
 * The optional AsciiDoc descriptor or its provider calls [install] once the JetBrains AsciiDoc plugin is enabled
 * and a renderer is wired. Until then [isAvailable] is false: the rendered viewers and actions answer "not
 * supported" for AsciiDoc and the text viewer stays in place, without loading AsciidoctorJ.
 *
 * [Runtime] is the hook for the first render (NFR-12). P4.6 integration implements it next to the optional
 * descriptor: [isReady] is false while Asciidoctor starts, and [Runtime.prepare] starts it and calls back when the
 * viewer may render. The viewer shows "Preparing AsciiDoc renderer..." in the meantime.
 */
object AsciiDocSupport {

    /** The Asciidoctor runtime behind the optional AsciiDoc integration. */
    interface Runtime {
        /** Whether a render can start now, without waiting for Asciidoctor. */
        fun isReady(): Boolean

        /**
         * Starts the runtime. Calls [onReady] once it can render, on the thread the runtime finished on; the viewer
         * schedules its own render. Must be idempotent while a start is in progress.
         */
        fun prepare(onReady: () -> Unit)
    }

    @Volatile
    private var runtime: Runtime? = null

    /** True when the optional AsciiDoc integration is installed; Markdown is always supported. */
    fun isAvailable(): Boolean = runtime != null

    /** Called by the optional AsciiDoc descriptor or provider when the renderer is wired. */
    fun install(runtime: Runtime) {
        this.runtime = runtime
    }

    /** Called when the AsciiDoc plugin or the plugin unloads; AsciiDoc diffs fall back to the text viewer again. */
    fun uninstall() {
        runtime = null
    }

    /** False while Asciidoctor starts; false too when no runtime is installed. */
    fun isReady(): Boolean = runtime?.isReady() ?: false

    /** Starts the runtime if there is one; does nothing while AsciiDoc support is unavailable. */
    fun prepare(onReady: () -> Unit) {
        runtime?.prepare(onReady)
    }
}
