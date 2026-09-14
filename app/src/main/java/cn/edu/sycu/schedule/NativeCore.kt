package cn.edu.sycu.schedule

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.webkit.*
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class ScheduleApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        verifyReleaseSignature()
        if (PrivacyConsent.accepted(this)) initializeConsentedServices()
    }

    private var servicesReady = false

    fun initializeConsentedServices() {
        if (servicesReady) return
        check(PrivacyConsent.accepted(this))
        SecretVault.initialize(this)
        // This dedicated WebView profile is never reused as the app's credential store.
        val directory =
            if (android.os.Build.VERSION.SDK_INT >= 28) "app_webview_login" else "app_webview"
        File(dataDir, directory).deleteRecursively()
        if (android.os.Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("login")
        WebView.setWebContentsDebuggingEnabled(false)
        servicesReady = true
    }

    private fun verifyReleaseSignature() {
            if (BuildConfig.DEBUG) return
            try {
            if (!BuildConfig.RELEASE_CERT_SHA256.matches(Regex("[0-9a-fA-F]{64}"))) terminateProcess()
            val signers = if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures
            }
            if (signers == null || signers.size != 1) terminateProcess()
            val signer = signers[0]
            val digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02X".format(it) }
            if (!digest.equals(BuildConfig.RELEASE_CERT_SHA256, ignoreCase = true)) terminateProcess()
            SigningIdentity.digest = digest
            } catch (_: Exception) {
                terminateProcess()
            }
    }
}

private fun terminateProcess(): Nothing {
    android.os.Process.killProcess(android.os.Process.myPid())
    kotlin.system.exitProcess(0)
}

internal object SigningIdentity {
    var digest: String = if (BuildConfig.DEBUG) "development" else ""
        internal set
}

class AuthFailure(val reason: String, message: String) : IllegalStateException(message)

object NativeCore {
    init {
        try {
            System.loadLibrary("sycu_core")
        } catch (_: LinkageError) {
            terminateProcess()
        }
    }

    @JvmStatic external fun call(input: String): String

    @JvmStatic
    external fun browser(view: WebView, request: WebResourceRequest?, options: String): String

    fun request(operation: Int, extra: JSONObject = JSONObject()): JSONObject {
        extra.put("op", operation)
        val response = JSONObject(call(extra.toString()))
        if (!response.optBoolean("ok"))
            throw if (response.optInt("code") == BuildConfig.ERR_AUTH) AuthFailure("login_required", "请重新完成学校登录")
            else AuthFailure("operation_failed", "操作未完成，请检查网络或稍后重试")
        return response.getJSONObject("data")
    }
}

/** Platform-only wrapping and persistence of opaque credential data. */
object SecretVault {
    private lateinit var file: AtomicFile
    private lateinit var application: Context

    fun initialize(context: Context) {
        application = context.applicationContext
        file = AtomicFile(File(context.noBackupFilesDir, "login.sealed"))
    }

    @JvmStatic fun context(): Context = application

    private fun key(): SecretKey {
        val identity = SigningIdentity.digest
        if (identity.isEmpty()) terminateProcess()
        val alias = "sycu.login.wrapper." + identity
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                }
                .generateKey()
    }

    @JvmStatic
    fun wrap(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return try {
            cipher.iv + cipher.doFinal(data)
        } finally {
            data.fill(0)
        }
    }

    @JvmStatic
    fun unwrap(data: ByteArray): ByteArray {
        require(data.size >= 28)
        if (SigningIdentity.digest.isEmpty()) terminateProcess()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey("sycu.login.wrapper." + SigningIdentity.digest, null) as? SecretKey
            ?: error("登录状态无法解密，请重新登录")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data.copyOfRange(12, data.size))
    }

    @JvmStatic
    fun write(data: ByteArray): ByteArray {
        val out = file.startWrite()
        try {
            out.write(data)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        return byteArrayOf()
    }

    @JvmStatic
    fun read(unused: ByteArray): ByteArray =
        if (file.baseFile.exists()) file.readFully() else byteArrayOf()

    @JvmStatic
    fun delete(unused: ByteArray): ByteArray {
        file.delete()
        return byteArrayOf()
    }
}

class BrowserHost(private val options: JSONObject) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val result = JSONObject(NativeCore.browser(view, request, options.toString()))
        return !result.optBoolean("ok") ||
            result.optJSONObject("data")?.optBoolean("allowed", true) == false
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (request.isForMainFrame) NativeCore.browser(view, request, options.toString())
        return null
    }

    override fun onPageFinished(view: WebView, url: String) {
        NativeCore.browser(view, null, options.toString())
    }

    companion object {
        fun create(context: Context, options: JSONObject): WebView =
            WebView(context).apply {
                importantForAutofill =
                    android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                settings.javaScriptEnabled = true
                // School initialization reads localStorage before binding login/eye handlers.
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = BrowserHost(options)
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage) = true
                    }
                tag = true
                CookieManager.getInstance().removeAllCookies {
                    if (tag == true)
                        NativeCore.browser(
                            this,
                            null,
                            JSONObject(options.toString()).put("begin", true).toString(),
                        )
                }
            }

        fun clear(view: WebView? = null) {
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
            WebStorage.getInstance().deleteAllData()
            view?.apply {
                tag = false
                webViewClient = WebViewClient()
                stopLoading()
                clearCache(true)
                clearHistory()
                destroy()
            }
        }
    }
}
