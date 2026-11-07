package com.decisionoverlay.checker

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Small floating badge containing only the final decision. */
class OverlayPanel(private val context:Context,private val windowManager:WindowManager,private val onClose:()->Unit){
 private fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
 private val width=minOf(dp(176),(context.resources.displayMetrics.widthPixels*.48f).toInt())
 private val params=WindowManager.LayoutParams(width,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,android.graphics.PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=dp(12);y=dp(90)}
 private fun label(size:Float,color:Int,bold:Boolean)=TextView(context).apply{setTextColor(color);setTextSize(TypedValue.COMPLEX_UNIT_SP,size);if(bold)setTypeface(typeface,Typeface.BOLD)}
 private val symbol=label(10f,MUTED,true)
 private val verdict=label(27f,DNT,true)
 private val price=label(10f,TEXT,true)
 private val close=label(16f,MUTED,true).apply{text="×";gravity=Gravity.CENTER;setPadding(dp(7),0,dp(3),0);setOnClickListener{onClose()}}
 private val card=LinearLayout(context).apply{
  orientation=LinearLayout.VERTICAL;background=GradientDrawable().apply{cornerRadius=dp(13).toFloat();setColor(Color.argb(238,12,17,27));setStroke(dp(1),Color.argb(150,91,125,182))};setPadding(dp(10),dp(6),dp(8),dp(7))
  addView(LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;addView(symbol,LinearLayout.LayoutParams(0,-2,1f));addView(close)})
  addView(LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;addView(verdict,LinearLayout.LayoutParams(0,-2,1f));addView(price)})
 }
 init { symbol.setOnTouchListener { _, e -> drag(e) } }
 val root:View=card;private var attached=false
 fun attach(){if(!attached){windowManager.addView(root,params);attached=true}}
 fun detach(){if(attached){try{windowManager.removeView(root)}catch(_:Exception){};attached=false}}
 fun render(s:EngineSnapshot){symbol.text=s.symbol;verdict.text=s.decision.verdict.name;verdict.setTextColor(when(s.decision.verdict){Verdict.UP->UP;Verdict.DOWN->DOWN;Verdict.DNT->DNT});price.text="$"+fmtPrice(s.decision.five.price)}
 private var sx=0;private var sy=0;private var rx=0f;private var ry=0f
 private fun drag(e:MotionEvent):Boolean{when(e.action){MotionEvent.ACTION_DOWN->{rx=e.rawX;ry=e.rawY;sx=params.x;sy=params.y;return true};MotionEvent.ACTION_MOVE->{val dx=(e.rawX-rx).toInt();val dy=(e.rawY-ry).toInt();if(abs(dx)+abs(dy)>dp(3)){params.x=(sx+dx).coerceAtLeast(0);params.y=(sy+dy).coerceAtLeast(0);if(attached)windowManager.updateViewLayout(root,params)};return true};MotionEvent.ACTION_UP->return true};return false}
 companion object{private val TEXT=Color.parseColor("#F4F7FB");private val MUTED=Color.parseColor("#9AA4B5");private val UP=Color.parseColor("#3DDC97");private val DOWN=Color.parseColor("#FF647C");private val DNT=Color.parseColor("#FFC45C")}
}
