package cn.edu.sycu.schedule

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic forms only: never submit an account/password to the school during regression tests. */
@RunWith(AndroidJUnit4::class)
class LoginFormTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun evaluate(view: WebView, script: String): String {
        var value = ""
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script) {
                value = it
                latch.countDown()
            }
        }
        assertTrue("JavaScript evaluation timed out", latch.await(10, TimeUnit.SECONDS))
        return value
    }

    @Test
    fun leavesInputsUntouchedAndPreservesSchoolClickHandlers() {
        lateinit var view: WebView
        val options =
            JSONObject()

        instrumentation.runOnMainSync {
            PrivacyConsent.accept(instrumentation.targetContext)
            (instrumentation.targetContext.applicationContext as ScheduleApplication).initializeConsentedServices()
            view = BrowserHost.create(instrumentation.targetContext, options)
        }
        try {
            var origin: String? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (origin?.startsWith("https://") != true && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync { origin = view.url }
                Thread.sleep(30)
            }
            assertNotNull("Native browser initialization failed", origin)
            instrumentation.runOnMainSync {
                assertTrue(
                    "School JavaScript requires DOM Storage",
                    view.settings.domStorageEnabled,
                )
                view.stopLoading()
                view.loadDataWithBaseURL(
                    origin,
                    """
                    <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                    <form><input type="hidden" name="username" id="username-fido"></form>
                    <form id="phoneFromId"><input name="username" id="username"></form>
                    <form id="pwdFromId"><input name="username" id="username">
                    <input name="passwordText" id="password" type="password">
                    <input name="password" type="hidden" id="saltPassword">
                    <input name="rememberMe" type="checkbox">
                    <button id="eye" type="button">eye</button><button id="submit" type="button">login</button></form>
                    <script>
                    document.addEventListener('DOMContentLoaded',()=>{
                      localStorage.getItem('synthetic-test');
                      document.querySelector('#eye').onclick=()=>{document.querySelector('#password').type='text'};
                      document.querySelector('#submit').onclick=()=>{window.submitClicks=(window.submitClicks||0)+1};
                      window.handlersReady=true;
                    });
                    </script>
                    """
                        .trimIndent(),
                    "text/html",
                    "UTF-8",
                    origin,
                )
            }
            val ready = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var filled = false
            while (!filled && System.nanoTime() < ready) {
                filled =
                    evaluate(
                        view,
                        "Boolean(window.handlersReady&&document.querySelector('[name=rememberMe]').checked)",
                    ) == "true"
                if (!filled) Thread.sleep(50)
            }
            assertTrue(
                "Remember-session option was not applied: " +
                    evaluate(
                        view,
                        "JSON.stringify({handlers:!!window.handlersReady,installed:!!window.__zhixuRememberSession,form:!!document.querySelector('#pwdFromId')})",
                    ),
                filled,
            )
            assertEquals(
                "true",
                evaluate(
                    view,
                    "document.querySelector('#username-fido').value===''&&document.querySelector('#phoneFromId [name=username]').value===''&&document.querySelector('#saltPassword').value===''",
                ),
            )
            assertEquals(
                "true",
                evaluate(
                    view,
                    "document.querySelector('#password').value===''&&document.querySelector('#pwdFromId [name=username]').value===''&&document.querySelector('[name=rememberMe]').checked",
                ),
            )
            assertEquals(
                "true",
                evaluate(
                    view,
                    "(()=>{document.querySelector('#eye').click();document.querySelector('#submit').click();return document.querySelector('#password').type==='text'&&window.submitClicks===1})()",
                ),
            )
            // A second page callback must not overwrite a user's correction.
            evaluate(view, "document.querySelector('#pwdFromId [name=username]').value='corrected'")
            instrumentation.runOnMainSync { NativeCore.browser(view, null, options.toString()) }
            assertEquals(
                "true",
                evaluate(
                    view,
                    "document.querySelector('#pwdFromId [name=username]').value==='corrected'",
                ),
            )
        } finally {
            instrumentation.runOnMainSync { BrowserHost.clear(view) }
        }
    }
}
