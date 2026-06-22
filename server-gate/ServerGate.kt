package com.ghosttype.security

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Server-side validation gate. Cloudflare Worker se verify karta hai
 * ke APK signature authorized hai ya nahi.
 *
 * AI bypass kar sakta hai lekin aapke worker ka URL change karke
 * apna worker laga sakta hai — LEKIN uske worker ke paas aapka
 * signing SHA nahi hoga → verify fail → { allowed: false } → brick.
 */
internal object ServerGate {

    // ⚠️ YAHAN APNA CLOUDFLARE WORKER URL DAALO
    // Deploy worker.js → Cloudflare dashboard worker URL copy karo
    private const val WORKER_URL = "https://divine-darkness-e443.chandtrick0.workers.dev"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json".toMediaType()

    /** POST /verify — server batata hai app allowed hai ya nahi */
    fun verify(ctx: Context): Boolean {
        return try {
            val signature = Obf.currentSigningSha(ctx)
            val deviceId = DeviceId.get(ctx)
            val body = JSONObject().apply {
                put("signature", signature)
                put("device_id", deviceId)
            }.toString()

            val request = Request.Builder()
                .url("$WORKER_URL/verify")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return false

            val json = JSONObject(response.body!!.string())
            json.optBoolean("allowed", false)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Naaya user approve karo — sirf ADMIN_TOKEN se kaam karega.
     * Ye function app mein nahi hoga, aap curl ya Postman se call karoge.
     *
     * Curl command:
     * curl -X POST https://your-worker.workers.dev/approve \
     *   -H "Content-Type: application/json" \
     *   -d '{"token":"your_admin_token","signature":"device_ki_signature","device_id":"device_ka_id"}'
     */
}
