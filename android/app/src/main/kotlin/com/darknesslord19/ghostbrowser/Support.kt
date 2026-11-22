package com.darknesslord19.ghostbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.webkit.WebView

class MediaItem(val url: String, val ref: String, val kind: String) {
    var body: String = ""
}

class Tab(val id: Int, val webView: WebView) {
    @Volatile var url: String = ""
    @Volatile var pageHost: String = ""
    @Volatile var playerRef: String = ""
    @Volatile var lockUrl: String = ""
    var title: String = "Yeni sekme"
    var loading = false
    var lastPopup = 0L
    var asked = ""
    var hidden = false
    var onFinished: (() -> Unit)? = null
    var cx = 300f
    var cy = 300f
    val media = ArrayList<MediaItem>()
    val isPlayer: Boolean get() = url.contains("/assets/player.html")
    val isInternal: Boolean get() = url.startsWith(MainActivity.ASSET)
}

/** TV/uzaktan kumanda için sanal imleç katmanı (dokunmaları geçirir). */
class CursorView(ctx: Context) : View(ctx) {
    var cx = 300f
    var cy = 300f
    private var until = 0L
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 70, 90) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    fun show() {
        until = SystemClock.uptimeMillis() + 3500
        invalidate()
        postInvalidateDelayed(3600)
    }

    override fun onDraw(canvas: Canvas) {
        if (SystemClock.uptimeMillis() < until) {
            canvas.drawCircle(cx, cy, 16f, fill)
            canvas.drawCircle(cx, cy, 16f, ring)
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false
}

object MediaScript {
    private const val BODY = """(()=>{try{
const vs=[...document.querySelectorAll('video')].filter(v=>v.readyState>0||v.currentSrc||v.src);
if(!vs.length)return null;
vs.sort((a,b)=>((!b.paused)-(!a.paused))||((b.videoWidth*b.videoHeight)-(a.videoWidth*a.videoHeight)));
const v=vs[0],cmd=__CMD__,arg=__ARG__,P=window.__ghostPlayer;
switch(cmd){
case'toggle':v.paused?v.play():v.pause();break;
case'play':v.play();break;case'pause':v.pause();break;
case'seek':v.currentTime=Math.max(0,Math.min(v.duration||1e9,v.currentTime+arg));break;
case'seekTo':v.currentTime=arg*(v.duration||0);break;
case'vol':v.muted=false;v.volume=Math.max(0,Math.min(1,v.volume+arg));break;
case'mute':v.muted=!v.muted;break;
case'speed':{const r=[0.5,0.75,1,1.25,1.5,2];const i=r.findIndex(x=>x>=v.playbackRate-0.001);v.playbackRate=r[((i<0?2:i)+1)%r.length];break}
case'fs':if(P&&P.fullscreen)P.fullscreen();else if(document.fullscreenElement)document.exitFullscreen();else{const c=v.closest('[class*="player" i],[id*="player" i]')||v;(c.requestFullscreen||c.webkitRequestFullscreen).call(c)}break;
case'subs':if(P&&P.toggleSubs)P.toggleSubs();else{const t=[...v.textTracks];if(t.length){const on=t.some(x=>x.mode==='showing');t.forEach(x=>x.mode=on?'disabled':'showing')}}break;
}
return{playing:!v.paused&&!v.ended,t:v.currentTime||0,d:isFinite(v.duration)?v.duration:0,vol:v.volume,muted:v.muted,rate:v.playbackRate,fs:!!document.fullscreenElement}
}catch(e){return null}})()"""

    fun build(cmd: String, arg: Double?): String =
        BODY.replace("__CMD__", org.json.JSONObject.quote(cmd)).replace("__ARG__", if (arg == null) "null" else arg.toString())
}
