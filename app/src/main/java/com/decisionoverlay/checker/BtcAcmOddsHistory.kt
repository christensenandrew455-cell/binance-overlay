package com.decisionoverlay.checker

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Download 30 FULL New York sessions, 10am-11pm, day-by-day (roughly
 * 25k 1-minute candles, not 60k+). Separate oldest 20 days from newest 10.
 * The very same 60-candle ACM algorithm is used in live and historical mode.
 */
object BtcAcmOddsHistory {
    private val zone = ZoneId.of("America/New_York")
    private const val MINUTE = 60_000L
    private const val DAY_COUNT = 30
    private const val TRAIN_DAYS = 20
    private const val MAX_AGE = 24L * 60 * 60 * 1000
    private const val PREF = "btc_acm_odds_model_v1"
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).build()

    fun load(context: Context, progress: (String)->Unit,
        canceled: ()->Boolean): BtcAcmOdds.Model {
        val prefs = context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        val savedAt = prefs.getLong("at",0L)
        if (System.currentTimeMillis()-savedAt in 0 until MAX_AGE) {
            val cache = BtcAcmOdds.Model.decode(
                prefs.getString("data","").orEmpty())
            if (cache!=null && cache.trainMarkets>=500 && cache.testMarkets>=200) {
                progress("Using cached 30-day price model (" +
                    cache.trainMarkets+" older / "+cache.testMarkets+" newer markets)")
                return cache
            }
        }
        val model=BtcAcmOdds.Model()
        val today=LocalDate.now(zone)
        var skipped=0
        var analyzed=0
        for (age in DAY_COUNT downTo 1) {
            check(!canceled()) { "Canceled calibration" }
            val day=today.minusDays(age.toLong())
            val data=download(day,canceled)
            if (data.size<700) {
                skipped++
                progress("Historical day incomplete: "+day+", got "+data.size+" candles")
                continue
            }
            val start=day.atTime(10,0).atZone(zone).toInstant().toEpochMilli()
            for (quarter in 0 until 52) {
                val market=start + quarter*15L*MINUTE
                val target=data[market-MINUTE]?.close ?: continue
                val end=data[market+14L*MINUTE]?.close ?: continue
                if (target<=0 || end<=0)continue
                val samples=mutableListOf<BtcAcmOdds.Sample>()
                for (left in 14 downTo 1) {
                    val observedAt=market+(14L-left)*MINUTE
                    val seen=data[observedAt]?.close ?: continue
                    val acm=BtcAcmMath.averageRange(data,observedAt,60) ?: continue
                    val side=if (seen>target)1 else if(seen<target)-1 else 0
                    if (side==0 || acm<=0)continue
                    val ratio=BtcAcmOdds.normalizedGap(
                        seen,target,acm,left*60.0)
                    if (!ratio.isFinite())continue
                    val yesWins=end>=target
                    val win=if (side==1) yesWins else !yesWins
                    samples.add(BtcAcmOdds.Sample(left,side,ratio,win))
                }
                if(samples.isNotEmpty()){
                    // Older data for calibration; newest 10 full days
                    // strictly held out to test every threshold.
                    model.addMarket(samples,age<=DAY_COUNT-TRAIN_DAYS)
                    analyzed++
                }
            }
            if((DAY_COUNT-age)%3==0 || age==1)
                progress("ACM60 probability calibration "+(DAY_COUNT-age+1)+
                    "/"+DAY_COUNT+" days — "+analyzed+" markets, "+
                    skipped+" incomplete days")
        }
        require(model.trainMarkets>=500 && model.testMarkets>=200){
            "Not enough complete historical BTC markets for fair-value alerts: " +
                model.trainMarkets+" train, "+model.testMarkets+" holdout"
        }
        prefs.edit().putLong("at",System.currentTimeMillis())
            .putString("data",model.encode()).apply()
        progress("Calibration ready: "+model.trainMarkets+" train / " +
            model.testMarkets+" held-out markets")
        return model
    }

    private fun download(day: LocalDate, canceled: ()->Boolean):
        Map<Long,BtcAcmMath.Candle> {
        val first=day.atTime(9,0).atZone(zone).toInstant().toEpochMilli()
        val last=day.atTime(23,0).atZone(zone).toInstant().toEpochMilli()-1
        val url="https://api.binance.us/api/v3/klines?symbol=BTCUSDT" +
            "&interval=1m&startTime="+first+"&endTime="+last+"&limit=1000"
        var lastError:Exception?=null
        repeat(3){ retry ->
            check(!canceled()) { "Canceled" }
            try {
                val data=http.newCall(Request.Builder().url(url).get().build())
                    .execute().use { response ->
                        val txt=response.body?.string().orEmpty()
                        if(!response.isSuccessful)
                            error("Binance.US HTTP "+response.code+": "+txt.take(80))
                        JSONArray(txt)
                    }
                val result=HashMap<Long,BtcAcmMath.Candle>()
                for(i in 0 until data.length()) {
                    val a=data.getJSONArray(i)
                    val c=BtcAcmMath.Candle(a.getLong(0),
                        a.getDouble(1),a.getDouble(2),
                        a.getDouble(3),a.getDouble(4))
                    if(c.valid())result[c.at]=c
                }
                return result
            } catch(e:Exception) {
                lastError=e
                if(retry<2)Thread.sleep(1000L*(retry+1))
            }
        }
        throw lastError ?: IllegalStateException("Historical BTC candles unavailable")
    }
}
