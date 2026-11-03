package com.deltaforce.alert.data

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * moligod.com 行情接口客户端。
 *
 * 实时价:  GET /api/market/ammo-choice-prices
 *   响应 = 加密字节流: [版本字节=1][12B IV][AES-GCM 密文+tag]，AAD="ammo-choice-prices-v1"，
 *   密钥 = SHA-256(响应头 x-ammo-choice-key)，解密后 gzip -> JSON {"items":[{object_id,name,price,updated_at,...}]}
 * 历史:   GET /api/market/history?name=<名>&interval=1h&limit=<N>&lookup=name
 *   -> {"data":[{time:"MM-DD HH:MM",avg,min,max,last}]}（北京时间）
 */
object PriceApi {
    private const val BASE = "https://moligod.com/api/market"
    private const val USER_AGENT = "DeltaBulletAlert/1.0"

    private fun httpGet(url: String): Triple<Int, Map<String, String>, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "*/*")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            val code = conn.responseCode
            val headers = mutableMapOf<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v.isNotEmpty()) headers[k.lowercase()] = v[0]
            }
            val body = if (code in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else {
                conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }
            return Triple(code, headers, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun decryptAmmoPayload(body: ByteArray, key: String): ByteArray {
        require(body.isNotEmpty() && body[0].toInt() == 1) { "payload format changed" }
        val iv = body.copyOfRange(1, 13)
        val ct = body.copyOfRange(13, body.size)
        val aesKey = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("ammo-choice-prices-v1".toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(ct)
        return GZIPInputStream(ByteArrayInputStream(plain)).use { it.readBytes() }
    }

    /** 拉取实时价（55 种子弹）。失败抛异常。 */
    fun fetchCurrentPrices(): List<BulletPrice> {
        val url = "$BASE/ammo-choice-prices?t=${System.currentTimeMillis()}"
        var backoff = 1_000L
        var attempt = 0
        while (attempt < 3) {
            val (code, headers, body) = httpGet(url)
            when (code) {
                in 200..299 -> {
                    val key = headers["x-ammo-choice-key"].orEmpty()
                    if (key.isEmpty()) throw IllegalStateException("no key header")
                    val json = JSONObject(String(decryptAmmoPayload(body, key), Charsets.UTF_8))
                    val arr = json.getJSONArray("items")
                    return buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            add(
                                BulletPrice(
                                    objectId = o.optString("object_id"),
                                    name = o.optString("name"),
                                    // 容忍小数价格，避免被 optInt 截断
                                    price = o.optDouble("price", 0.0).roundToInt(),
                                    updatedAtMs = o.optLong("updated_at_ms", 0L),
                                )
                            )
                        }
                    }
                }
                429 -> {
                    attempt++
                    Thread.sleep(retryDelayMs(headers, backoff))
                    backoff *= 2
                }
                else -> throw IllegalStateException("HTTP $code")
            }
        }
        throw IllegalStateException("rate limited")
    }

    /** 拉取历史逐小时均价序列（用于计算参考均值）。 */
    fun fetchHistoryAvg(name: String, interval: String = "1h", limit: Int = 720): List<Double> {
        val url = "$BASE/history?name=${java.net.URLEncoder.encode(name, "UTF-8")}&interval=$interval&limit=$limit&lookup=name"
        var backoff = 1_000L
        var attempt = 0
        while (attempt < 3) {
            val (code, headers, body) = httpGet(url)
            when (code) {
                in 200..299 -> {
                    val json = JSONObject(String(body, Charsets.UTF_8))
                    val arr = json.getJSONArray("data")
                    return buildList {
                        for (i in 0 until arr.length()) {
                            add(arr.getJSONObject(i).optDouble("avg"))
                        }
                    }
                }
                429 -> {
                    attempt++
                    Thread.sleep(retryDelayMs(headers, backoff))
                    backoff *= 2
                }
                else -> throw IllegalStateException("HTTP $code")
            }
        }
        throw IllegalStateException("rate limited")
    }

    /** 服务端给了 Retry-After 就听它的，否则退回指数退避 */
    private fun retryDelayMs(headers: Map<String, String>, backoff: Long): Long {
        val secs = headers["retry-after"]?.trim()?.toLongOrNull() ?: return backoff
        return maxOf(backoff, secs * 1000L)
    }
}
