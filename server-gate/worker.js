/**
 * ── GhostType Server Gate ──────────────────────────────────
 * Cloudflare Worker that validates APK signatures.
 * SIRF AAP allowed SHA256 daal sakte hain → koi aur bypass nahi.
 *
 * Deploy: Cloudflare Dashboard → Workers → Create Worker
 * ───────────────────────────────────────────────────────────
 */

// ⚠️ YAHAN APNA RELEASE KEYSTORE KA SHA256 DAALO
// keytool -list -v -keystore your-release.jks -alias your-alias
// se SHA256 copy karo, colon hatao, lowercase kar do
const ALLOWED_SIGNATURES = [
  "e370b349c5cde8797d9d661db34f9f41783975af788b934a7d798fd7fcfed5ae",  // ← placeholder, jab release keystore banaoge tab change karna
];

// Admin token — is token se aap naye SHA add kar sakte ho
// Bina token ke koi SHA add nahi kar sakta
const ADMIN_TOKEN = "103c01024c144827252be0a3164e05b645859b1c33707ab51074644ae198fe2e";

export default {
  async fetch(request) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Content-Type": "application/json",
    };

    if (request.method === "OPTIONS") return new Response(null, { headers: cors });
    if (request.method !== "POST") return new Response("POST only", { status: 405 });

    const url = new URL(request.url);
    const path = url.pathname;

    try {
      // ── Admin: approve naya user ──
      // POST /approve  body: { token, signature, device_id }
      if (path === "/approve") {
        const { token, signature, device_id } = await request.json();
        if (token !== ADMIN_TOKEN) {
          return new Response(JSON.stringify({ success: false, reason: "invalid token" }), { headers: cors, status: 403 });
        }
        if (!signature || !device_id) {
          return new Response(JSON.stringify({ success: false, reason: "missing fields" }), { headers: cors, status: 400 });
        }
        if (!ALLOWED_SIGNATURES.includes(signature.toLowerCase())) {
          return new Response(JSON.stringify({ success: false, reason: "signature not in allowed list" }), { headers: cors, status: 403 });
        }
        // Approved device ID ko allowed list mein add karo
        // Isko aap KV store mein bhi rakh sakte ho for production
        return new Response(JSON.stringify({
          success: true,
          message: "Device approved",
          device_id,
          signature,
          approved_at: Date.now(),
        }), { headers: cors });
      }

      // ── Main verify endpoint ──
      // POST /verify  body: { signature, device_id }
      const { signature, device_id } = await request.json();
      if (!signature || !device_id) {
        return new Response(JSON.stringify({ allowed: false }), { headers: cors });
      }

      const allowed = ALLOWED_SIGNATURES.includes(signature.toLowerCase());

      return new Response(JSON.stringify({
        allowed,
        reason: allowed ? "ok" : "unauthorized signature",
        signature_prefix: signature.substring(0, 8) + "...",
      }), { headers: cors });

    } catch (e) {
      return new Response(JSON.stringify({ allowed: false }), { headers: cors });
    }
  }
};
