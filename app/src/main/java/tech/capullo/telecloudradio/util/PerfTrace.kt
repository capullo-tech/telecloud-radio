package tech.capullo.telecloudradio.util

import android.os.Trace

/**
 * Thin atrace-section helpers for perf measurement (docs/perf/). Sections show up under the
 * app tag in atrace captures on any debuggable build - the rig type. Zero behavior change; cheap enough
 * to keep in production.
 */
object PerfTrace {
    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    fun begin(name: String) = Trace.beginSection(name)
    fun end() = Trace.endSection()
}
