package cn.edu.sycu.schedule

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run only against a deliberately re-signed debug fixture, never against a published APK. */
class NativeIdentityRejectionTest {
    @Test fun changedSignerIsRejectedByNativeEntry() {
        val arguments = InstrumentationRegistry.getArguments()
        val invalidCall = arguments.getString("expectIllegalCall") == "true"
        assumeTrue(arguments.getString("expectIdentityRejection") == "true" || invalidCall)
        InstrumentationRegistry.getInstrumentation().sendStatus(1,
            android.os.Bundle().apply { putString("checkpoint", "entered") })
        NativeCore.call(JSONObject().put("op", if (invalidCall) 0 else BuildConfig.INITIALIZE).toString())
        fail("Unexpected return from rejected call")
    }
}
