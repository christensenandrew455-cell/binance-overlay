package com.decisionoverlay.checker

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * No Kalshi private key is ever imported into this app. It only sends
 * Android-Keystore-signed requests to the user's protected Vercel function.
 */
internal class KalshiPhoneApi(private val context: Context) {
    companion object {
        private const val API_URL = "https://binance-overlay.vercel.app/api/kalshi"
        private const val API_ROUTE = "/api/kalshi"
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5,TimeUnit.SECONDS)
        .readTimeout(12,TimeUnit.SECONDS)
        .callTimeout(18,TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private fun call(payload: JSONObject): JSONObject {
        val raw=payload.toString()
        val stamp=System.currentTimeMillis().toString()
        val signature=KalshiPhoneDevice.sign(stamp,"POST",API_ROUTE,raw)
        val request=Request.Builder().url(API_URL)
            .header("Accept","application/json")
            .header("X-Trader-Timestamp",stamp)
            .header("X-Trader-Signature",signature)
            // text/plain preserves the exact signed bytes for verification.
            .post(raw.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        return http.newCall(request).execute().use { res ->
            val text=res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val error=runCatching {JSONObject(text).optString("error")}.getOrNull()
                val reason=if(error.isNullOrBlank()) "HTTP ${res.code} (check Vercel access)"
                    else "HTTP ${res.code} $error"
                error("Vercel Kalshi connection: $reason")
            }
            JSONObject(text)
        }
    }
    data class Funds(val equityCents:Long,val cashCents:Long) {
        val dollars:String get()=BigDecimal(equityCents).movePointLeft(2).toPlainString()
    }
    fun balance():Funds {
        val j=call(JSONObject().put("action","balance"))
        val equity=j.getLong("equityCents")
        val cash=j.getLong("cashCents")
        require(equity>=0&&cash>=0&&cash<=equity){"Invalid Kalshi balance"}
        return Funds(equity,cash)
    }
    data class Market(val ticker:String,val closeTime:Instant,val status:String)
    fun currentMarket(now:Instant):Market? {
        val j=call(JSONObject().put("action","current_market"))
        val m=j.optJSONObject("market") ?: return null
        val ticker=m.getString("ticker")
        val close=Instant.parse(m.getString("close_time"))
        if(!ticker.startsWith("KXBTC15M-")||now.isBefore(close.minusSeconds(900))||
            !now.isBefore(close))return null
        return Market(ticker,close,m.optString("status"))
    }
    fun market(ticker:String):JSONObject {
        require(Regex("^KXBTC15M-[A-Za-z0-9-]+$").matches(ticker))
        return call(JSONObject().put("action","market").put("ticker",ticker))
            .getJSONObject("market")
    }
    fun ask(ticker:String,outcome:String):BigDecimal? {
        require(outcome=="YES"||outcome=="NO")
        val j=call(JSONObject().put("action","ask").put("ticker",ticker)
            .put("outcome",outcome))
        if(j.isNull("ask"))return null
        return BigDecimal(j.getString("ask"))
    }
    data class Submitted(val clientId:String,val orderId:String?)
    fun submit(ticker:String,outcome:String,count:Int,clientId:String):Submitted {
        require(outcome=="YES"||outcome=="NO")
        require(ticker.startsWith("KXBTC15M-")&&count in 1..1000)
        val result=call(JSONObject().put("action","order").put("ticker",ticker)
            .put("outcome",outcome).put("count",count))
        return Submitted(
            result.optString("clientId").ifBlank {clientId},
            result.optString("orderId").takeIf { it.isNotBlank() && it!="null" })
    }
    fun fills(orderId:String):List<JSONObject> {
        val result=call(JSONObject().put("action","fills").put("ticker","KXBTC15M-VALID")
            .put("orderId",orderId))
        val rows=result.optJSONArray("fills")?:JSONArray()
        return (0 until rows.length()).map {rows.getJSONObject(it)}
    }
    fun newClientId():String=UUID.randomUUID().toString()
}
