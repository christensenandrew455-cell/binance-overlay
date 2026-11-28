package com.decisionoverlay.checker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Single-account, durable journal. Never stores API secrets. */
internal class KalshiPhoneLedger(context: Context) :
    SQLiteOpenHelper(context, "kalshi_phone_trades.db", null, 1) {
    private val zone = ZoneId.of("America/New_York")
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE state (
            id INTEGER PRIMARY KEY CHECK(id=1),
            active INTEGER NOT NULL, locked INTEGER NOT NULL, anchor INTEGER NOT NULL,
            wager INTEGER NOT NULL, equity INTEGER, cash INTEGER, updated INTEGER, reason TEXT)""")
        db.execSQL("INSERT INTO state(id,active,locked,anchor,wager,reason) VALUES (1,0,0,0,0,'STOPPED')")
        db.execSQL("""CREATE TABLE orders(
            ticker TEXT PRIMARY KEY, outcome TEXT NOT NULL, client_id TEXT NOT NULL UNIQUE,
            count INTEGER NOT NULL, status TEXT NOT NULL, order_id TEXT, created INTEGER NOT NULL,
            settled INTEGER, paid_cents INTEGER, fees_cents INTEGER, pnl_cents INTEGER, returned_cents INTEGER)""")
        db.execSQL("""CREATE TABLE audit (
            id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, name TEXT NOT NULL, message TEXT NOT NULL)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, next: Int) = Unit

    data class Status(val active: Boolean, val locked: Boolean, val anchor: Long,
        val wager: Long, val equity: Long?, val cash: Long?, val updated: Long, val reason: String)

    @Synchronized fun status(): Status {
        readableDatabase.rawQuery(
            "SELECT active,locked,anchor,wager,equity,cash,updated,reason FROM state WHERE id=1", null
        ).use {
            check(it.moveToFirst())
            return Status(it.getInt(0)!=0, it.getInt(1)!=0, it.getLong(2), it.getLong(3),
                if(it.isNull(4)) null else it.getLong(4),
                if(it.isNull(5)) null else it.getLong(5),
                if(it.isNull(6)) 0L else it.getLong(6), it.getString(7))
        }
    }

    @Synchronized fun log(name: String, message: String) {
        writableDatabase.execSQL("INSERT INTO audit(at,name,message) VALUES (?,?,?)",
            arrayOf(System.currentTimeMillis(), name.take(80), message.take(3000)))
    }

    @Synchronized fun updateFunds(funds: KalshiPhoneApi.Funds) {
        writableDatabase.execSQL("UPDATE state SET equity=?,cash=?,updated=? WHERE id=1",
            arrayOf(funds.equityCents,funds.cashCents,System.currentTimeMillis()))
    }

    @Synchronized fun hasUncertainOrders(): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM orders WHERE status='UNCERTAIN' LIMIT 1",null
        ).use { return it.moveToFirst() }
    }

    @Synchronized fun activate(funds: KalshiPhoneApi.Funds): String {
        val s=status()
        if (s.locked) return "LOCKED: review last force stop first"
        if (hasUncertainOrders()) {
            forceStop("UNKNOWN_ORDER: review Kalshi executions")
            return "UNKNOWN_ORDER: review before restarting"
        }
        if (funds.equityCents < 10000) return "Balance below $100"
        val anchor=if(s.anchor == 0L) funds.equityCents else s.anchor
        val wager=if(s.anchor == 0L) (anchor * 30L / 100).coerceAtMost(60000)
                  else s.wager
        writableDatabase.execSQL("""UPDATE state SET active=1,anchor=?,wager=?,reason='TRADING_ACTIVE',
            equity=?,cash=?,updated=? WHERE id=1""",arrayOf(anchor,wager,
                funds.equityCents,funds.cashCents,System.currentTimeMillis()))
        log("START", "Equity=${funds.equityCents}c stake=${wager}c anchor=${anchor}c")
        return "ACTIVE"
    }

    @Synchronized fun stop(reason: String = "USER_STOP") {
        writableDatabase.execSQL("UPDATE state SET active=0,reason=? WHERE id=1",
            arrayOf(reason.take(100)))
        log("STOP",reason)
    }

    @Synchronized fun forceStop(reason: String) {
        writableDatabase.execSQL("UPDATE state SET active=0,locked=1,reason=? WHERE id=1",
            arrayOf(reason.take(100)))
        log("FORCE_STOP",reason)
    }

    @Synchronized fun resetAfterReview() {
        if(status().active) error("Stop trading before resetting")
        writableDatabase.execSQL("""UPDATE orders SET status='REVIEWED_UNCERTAIN'
            WHERE status='UNCERTAIN'""")
        writableDatabase.execSQL("""UPDATE state SET anchor=0,wager=0,locked=0,
            active=0,reason='REVIEWED_RESET' WHERE id=1""")
        log("MANUAL_RESET","Reviewed lockout; old trade logs retained")
    }

    @Synchronized fun checkRisk(funds: KalshiPhoneApi.Funds): Long? {
        updateFunds(funds)
        var s=status()
        if (!s.active || s.locked) return null
        if(s.anchor <= 0) { forceStop("INVALID_ANCHOR"); return null }
        if (funds.equityCents <= s.anchor / 2) {
            forceStop("50% drawdown: automatic trading locked")
            return null
        }
        var anchor=s.anchor
        var wager=s.wager
        while (anchor <= Long.MAX_VALUE/2 && funds.equityCents >= anchor*2) {
            anchor*=2
            wager=(anchor*30/100).coerceAtMost(60000)
        }
        if (anchor != s.anchor) {
            writableDatabase.execSQL("UPDATE state SET anchor=?,wager=? WHERE id=1",
                arrayOf(anchor,wager))
            log("DOUBLED", "Anchor=${anchor}c fixed stake=${wager}c")
        }
        if(funds.equityCents < 10000 || funds.cashCents < wager) return null
        return wager
    }

    @Synchronized fun hasMarket(ticker: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM orders WHERE ticker=?", arrayOf(ticker)).use {
            return it.moveToFirst()
        }
    }

    /** Durable claim before placing order; a timeout never causes an automatic retry. */
    @Synchronized fun claim(ticker: String, outcome: String, count: Int,
                             clientId: String): Boolean {
        if (!status().active || hasMarket(ticker)) return false
        try {
            writableDatabase.execSQL("""INSERT INTO orders
                (ticker,outcome,client_id,count,status,created) VALUES (?,?,?,?,?,?)""",
                arrayOf(ticker,outcome,clientId,count,"UNCERTAIN",System.currentTimeMillis()))
            log("ORDER_SENT","ticker=$ticker outcome=$outcome contracts=$count id=$clientId max=60c")
            return true
        } catch(_: Exception) { return false }
    }

    @Synchronized fun acknowledge(ticker: String, orderId: String) {
        writableDatabase.execSQL(
            "UPDATE orders SET order_id=?,status='PENDING_SETTLEMENT' WHERE ticker=?",
            arrayOf(orderId,ticker))
        log("ORDER_RESPONSE","ticker=$ticker order_id=$orderId")
    }

    @Synchronized fun pending(): List<Triple<String,String,String>> {
        val rows=mutableListOf<Triple<String,String,String>>()
        readableDatabase.rawQuery(
            "SELECT ticker,outcome,order_id FROM orders WHERE status='PENDING_SETTLEMENT'",null
        ).use { while(it.moveToNext()) rows.add(Triple(it.getString(0),it.getString(1),it.getString(2))) }
        return rows
    }

    @Synchronized fun noFill(ticker: String) {
        writableDatabase.execSQL("UPDATE orders SET status='NO_FILL' WHERE ticker=?",arrayOf(ticker))
        log("NO_FILL","market=$ticker")
    }

    @Synchronized fun settle(ticker: String, paid: Long, fee: Long, returned: Long): Long {
        val pnl=returned-paid-fee
        val time=System.currentTimeMillis()
        writableDatabase.execSQL("""UPDATE orders SET status='SETTLED',settled=?,
            paid_cents=?,fees_cents=?,returned_cents=?,pnl_cents=? WHERE ticker=?
            AND status='PENDING_SETTLEMENT'""",
            arrayOf(time,paid,fee,returned,pnl,ticker))
        log("SETTLED","ticker=$ticker cost=${paid}c fees=${fee}c returned=${returned}c pnl=${pnl}c")
        return pnl
    }

    data class Today(val wins: Int, val losses: Int, val profit: Long, val loss: Long) {
        val net: Long get() = profit-loss
    }
    @Synchronized fun today(): Today {
        val date=Instant.now().atZone(zone).toLocalDate()
        var w=0;var l=0;var pos=0L;var neg=0L
        readableDatabase.rawQuery(
            "SELECT settled,pnl_cents FROM orders WHERE status='SETTLED'",null
        ).use {
            while(it.moveToNext()) {
                if(Instant.ofEpochMilli(it.getLong(0)).atZone(zone).toLocalDate()!=date) continue
                val cents=it.getLong(1)
                if(cents>0) {w++;pos+=cents}
                if(cents<0) {l++;neg-=cents}
            }
        }
        return Today(w,l,pos,neg)
    }

    private fun csv(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\n", " ") + "\""

    /** Export every trade and audit event, including failed and uncertain orders. */
    @Synchronized fun exportCsv(): String {
        val out = StringBuilder("type,time_utc,ticker,outcome,client_id,order_id,count,status,paid_cents,fee_cents,returned_cents,pnl_cents,event,message\n")
        readableDatabase.rawQuery("""SELECT ticker,outcome,client_id,order_id,count,status,
            created,settled,paid_cents,fees_cents,returned_cents,pnl_cents
            FROM orders ORDER BY created ASC""",null).use { rows ->
            while(rows.moveToNext()) {
                val fields=listOf("ORDER",
                    Instant.ofEpochMilli(rows.getLong(6)).toString(),
                    rows.getString(0),rows.getString(1),rows.getString(2),
                    if(rows.isNull(3)) "" else rows.getString(3),
                    rows.getInt(4).toString(),rows.getString(5),
                    if(rows.isNull(8)) "" else rows.getLong(8).toString(),
                    if(rows.isNull(9)) "" else rows.getLong(9).toString(),
                    if(rows.isNull(10)) "" else rows.getLong(10).toString(),
                    if(rows.isNull(11)) "" else rows.getLong(11).toString(),"","")
                out.append(fields.joinToString(",") { csv(it) }).append('\n')
            }
        }
        readableDatabase.rawQuery(
            "SELECT at,name,message FROM audit ORDER BY id ASC",null
        ).use { rows ->
            while(rows.moveToNext()) {
                val fields=listOf("EVENT",Instant.ofEpochMilli(rows.getLong(0)).toString())+
                    List(10){""}+listOf(rows.getString(1),rows.getString(2))
                out.append(fields.joinToString(",") { csv(it) }).append('\n')
            }
        }
        return out.toString()
    }

    @Synchronized fun recentEvents(): String {
        val result=mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT at,name,message FROM audit ORDER BY id DESC LIMIT 22",null
        ).use {
            while (it.moveToNext()) {
                val local=Instant.ofEpochMilli(it.getLong(0)).atZone(zone).toLocalTime()
                result.add("${local.toString().take(8)}  ${it.getString(1)}  ${it.getString(2)}")
            }
        }
        return result.joinToString("\n").ifEmpty { "No trades or events yet." }
    }
}
