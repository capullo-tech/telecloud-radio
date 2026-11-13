package tech.capullo.telecloudradio

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level smoke test - the payload of the CI emulator matrix
 * (.github/workflows/EmulatorSmoke.yml, one leg per API level 23-37).
 *
 * The landing page on a fresh install is the AuthScreen (AppNavHost roots the backstack at
 * AuthRoute, and an emulator guest has no Telegram session), so "the landing page loads
 * normally" means the sign-in scaffold composes: the "Sign in with Telegram" headline and the
 * phone form. Nothing here signs in - TDLib talks to Telegram's servers underneath the form,
 * but the form itself is local UI, and any TDLib/native-lib failure on an old API takes the
 * process down and fails the run the loud way.
 *
 * UiAutomator rather than a Compose test rule, deliberately: the auth screen can sit under an
 * indefinite spinner while TDLib initializes (autoPhonePending), and a Compose rule's idle
 * synchronization never settles under an infinite animation - every matrix leg would die in
 * waitForIdle instead of testing anything. UiAutomator polls the window hierarchy directly.
 */
@RunWith(AndroidJUnit4::class)
class MainSmokeTest {
    @Test
    fun coldStartLoadsAuthScreenWithPhoneForm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)

        // Cold start through the shell's am - the exact resolution path the adb CLI uses. NOT
        // Context.startActivity from the app's own package: on a fresh API 37 guest, in-process
        // resolution fails two different ways (getLaunchIntentForPackage null; explicit
        // component ActivityNotFoundException) for a package whose process is demonstrably
        // alive and running this very test, while the same launch via am works. MainActivity
        // is the manifest's launcher activity, so this is still the real entry point, just
        // resolved system-side. No pressHome first: on a fresh boot the launcher is already
        // foreground, the app has never run (nothing to clear), and re-foregrounding the
        // launcher is what re-triggered its early-boot crash loop on the 37.0 google_apis
        // image.
        shell("am start -W -n tech.capullo.telecloudradio/.MainActivity").also(::println)

        // Bar 1, landing: the auth screen's stable identity is the phone-form headline IN THE
        // APP'S OWN WINDOW - a bare text match can hit the launcher's icon label during the
        // open transition (seen on API 36 in capullo-tube, where the text matched before the
        // activity was even Displayed). Present in the fresh-install state (no saved Telegram
        // session on an emulator guest): WaitParameters/Unknown with setupStep == Phone.
        // 30s, not 5: cold Hilt graph + TDLib native load on a swiftshader-rendered emulator
        // is slow.
        val authRendered = device.wait(
            Until.hasObject(By.pkg("tech.capullo.telecloudradio").text("Sign in with Telegram")),
            30_000,
        )
        check(authRendered) { "auth landing never rendered: sign-in headline missing after 30s" }
        shell("screencap -p /sdcard/smoke-home.png")

        // Bar 2, the form actually composed - headline alone can precede the inputs if the
        // screen half-composes. The field label lives in the same PhoneForm column, so its
        // presence says the interactive landing is really there, not a headline over a spinner.
        val phoneFormLanded = device.wait(
            Until.hasObject(By.pkg("tech.capullo.telecloudradio").text("Phone number")),
            30_000,
        )
        if (!phoneFormLanded) {
            // Preserve what the page DID show (progress spinner, error text) before failing -
            // "form never appeared" without the texts is a debugging dead end.
            println("visible app texts: " + visibleAppTexts(device).joinToString(" | "))
            shell("screencap -p /sdcard/smoke-content.png")
        }
        check(phoneFormLanded) { "auth landing rendered but the phone form never appeared in 30s" }
        shell("screencap -p /sdcard/smoke-content.png")

        // And the window must STAY there - a window that vanishes right after first frame
        // (activity finished, task cleared) is not a successful launch. Until.gone instead of
        // a one-shot hasObject: an instantaneous probe can lose the race with first-frame
        // composition (API 36); polling for disappearance cannot false-positive. (A process
        // crash needs no assertion here - it takes the instrumentation down with it and fails
        // the run the loud way.)
        val windowVanished = device.wait(Until.gone(By.pkg("tech.capullo.telecloudradio")), 2_000)
        check(!windowVanished) { "app window vanished after first frame - launch was not stable" }
    }

    /** All non-blank texts currently visible in the app's windows, for failure transcripts. */
    private fun visibleAppTexts(device: UiDevice): List<String> =
        device.findObjects(By.pkg("tech.capullo.telecloudradio"))
            .mapNotNull { it.text }
            .filter { it.isNotBlank() }

    /**
     * Runs a shell command as the adb CLI would, drained and returned (its output lands in
     * the transcript via the callers' println).
     */
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation()
        .uiAutomation.executeShellCommand(command)
        .use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes().decodeToString() }
}
