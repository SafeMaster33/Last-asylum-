package com.safemaster.lastasylum

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.io.FileOutputStream
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class ScannerService : AccessibilityService() {
 companion object { private const val PINCH_FACTOR = 1.42f; var instance:ScannerService?=null; private set }
 private val handler=Handler(Looper.getMainLooper())
 private val executor=Executors.newSingleThreadExecutor()
 private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
 private var moveDelayMs=2500L
 private var scaleMode=1
 private var ignoreKinds=setOf<String>()
 private var scanStart=0L
 private var lastLog=""
 private val journal=ArrayDeque<String>()
 private var rowSteps=0
 private var maxRowSteps=90
 private var verticalNoMotion=0
 private var maxMoveRetries=5
 private var moveRetries=0
 private var allowedKinds=setOf(0,1,2)
 private val russian by lazy { RussianOcr(this) }
 private var runId = 0L
 private var planNavigation = false
 private data class Swipe(val x1:Float,val y1:Float,val x2:Float,val y2:Float)
 private var nextSwipe: Swipe? = null
 private var navigationStop: String? = null
 private var firstOverview: List<FocusMark>? = null
 private data class PendingBase(val mark:FocusMark, val retry:Int=0)
 private val queue = ArrayDeque<PendingBase>()
 private var undecided = 0
 private var totalUnresolved = 0
 private data class Snapshot(val colors:IntArray, val map:VisualRegistration.Frame, val layout:MapLayout,
                             val measured:Map<VisualRegistration.Frame,MapTransform?>,
                             var landmarks:List<FocusMark> = emptyList())
 private data class CameraStep(val pan:FocusPoint?, val pivot:FocusPoint, val sample:Snapshot,
                               val factor:Float=PINCH_FACTOR)
 private data class Inspection(
     val base:PendingBase, val tracker:FocusTracker, val original:Snapshot,
     val history:MutableList<CameraStep> = mutableListOf(),
     var waiting:CameraStep? = null, var undoing:Boolean = false, var undoWait:Int = 0,
     var restore:Boolean = false, var retry:Boolean = false, var zooms:Int = 0, var sentGesture:Boolean = false,
     var lastName:String? = null, var manualOther:String? = null, var hits:Int = 0, var reads:Int = 0,
     var predictionFrame:Snapshot? = null, var corrections:Int=0, var panGain:Float=1f,
     var rechecked:Boolean=false
 )
 private var current:Inspection? = null
 private var originalSize:Pair<Int,Int>? = null
 private var viewInitialized = false
 private var settling = false
 private var settleFrames = 0
 private var lastSettle: Snapshot? = null
 private fun resetInspection() {
     current=null; queue.clear(); nextSwipe=null; viewInitialized=false
     originalSize=null; undecided=0; totalUnresolved=0; planNavigation=false; settling=false; lastSettle=null
     navigationStop=null; firstOverview=null
 }
 private fun schedule(ms:Long) {
     val id=runId
     handler.postDelayed({ if(running && runId==id) scan() },ms)
 }

 private var diagnosticMode=false
 private var diagnosticChecks=0
 private var diagnosticPair=0
 private var pendingDiagnosticBefore:Bitmap?=null
 private var diagnosticDirectory:File?=null
 private var diagnosticVerticalDone=false
 private var uncertainHorizontal=0
 private var uncertainVertical=0
 private var verticalTransitionPending=false
 private var edgeProbeStage=0 // 0 normal, 1 edge reverse, 2 edge restore, 3 uncertain reverse, 4 uncertain restore
 private var recoveryCycles=0
 private var recoveryGestureCount=0
 private var edgeEvidence=0
 private var probeReverseChanged=false
 private var movementSamples=mutableListOf<Double>()
 private var overlay:TextView?=null
 private var windowManager:WindowManager?=null
 private var running=false
 private var busy=false
 private var target="Doctor8rtx6hjd"
 private var targetLevel=20
 private var searchMode=0
 private var direction=1 // 1: right on map, -1: left on map
 private var edgeCount=0
 private var row=0
 private var previousSample:Snapshot?=null
 private var dispatchedSwipe:Swipe?=null
 private var checkingMovement=false
 private var verticalCheck=false
 private var maxRows=150
 // Visual motion is measured by matching translated map samples, not by a
 // fixed whole-screen colour-difference threshold (which gave false edges).
 private val sampleCols=72
 private val sampleRows=80
 private var horizontalStillChecks=0
 private var verticalStillChecks=0
 private var confirmedHorizontalMoves=0
 private var attemptedHorizontalMoves=0
 private var awaitingMotion=false
 private var tone:ToneGenerator?=null
 private var width=0
 private var height=0
 var status="Ожидание"; private set
 override fun onServiceConnected() {
     instance=this
     val p=getSharedPreferences("scanner",MODE_PRIVATE)
     val ignored=listOf("resources","mobs","enemies","neutral","allies","castles").filter { p.getBoolean("ignore_$it",false) }.toSet()
     configure(p.getString("name",target).orEmpty(),p.getInt("level",20),p.getInt("mode",0),
         p.getLong("delay",2500),p.getInt("retries",5),scale=p.getInt("scale",1),ignored=ignored,
         diagnostic=p.getBoolean("diagnostic",false))
     status="Служба готова"; showOverlay()
 }
 override fun onAccessibilityEvent(event:AccessibilityEvent?) {}
 override fun onInterrupt() { stopScanning() }
 override fun onDestroy() { hideOverlay(); stopScanning(); executor.execute { recognizer.close(); russian.close() }; tone?.release(); executor.shutdown(); instance=null; super.onDestroy() }
 fun configure(name:String, level:Int, mode:Int=0, delayMs:Long=2500, retries:Int=5, kinds:Set<Int> = setOf(0,1,2), scale:Int=0, ignored:Set<String> = emptySet(), diagnostic:Boolean=false) { if(running) return; diagnosticMode=diagnostic; scaleMode=scale; ignoreKinds=ignored; target=name.trim(); targetLevel=level; searchMode=mode.coerceIn(0,2); moveDelayMs=delayMs.coerceIn(500,15000); maxMoveRetries=retries.coerceIn(1,12); allowedKinds=kinds; resetNavigation() }
 fun startScanning() { if(running) return; runId++; resetNavigation(); scanStart=System.currentTimeMillis(); running=true; busy=false
  diagnosticDirectory=if(diagnosticMode) File(getExternalFilesDir(null) ?: filesDir,"navigation_${scanStart}").also { it.mkdirs() } else null
  val mode=listOf("имя и уровень","уровень","имя")[searchMode]
  report(if(diagnosticMode) "${installedVersion()} · Диагностика 3+1; кадры: ${diagnosticDirectory?.absolutePath}" else "${installedVersion()} · Поиск: $mode · ${if(searchMode!=1) target else ""} ${if(searchMode!=2) "ур. $targetLevel" else ""} · ${if(scaleMode==0) "масштаб фиксирован" else "автоувеличение"}")
  schedule(1200) }
 private fun saveDiagnosticFrame(bitmap:Bitmap,folder:File,pair:Int,suffix:String) {
  FileOutputStream(File(folder,"pair_${pair}_${suffix}.jpg")).use { bitmap.compress(Bitmap.CompressFormat.JPEG,88,it) }
 }
 fun stopScanning() { runId++; running=false; busy=false; previousSample=null; pendingDiagnosticBefore?.recycle(); pendingDiagnosticBefore=null; report("Остановлено пользователем") }
 private fun report(message:String) { status=message; lastLog=message; val time=SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date()); journal.addLast("$time | $message"); while(journal.size>120) journal.removeFirst(); updateOverlay() }
 fun diagnostics():String = journal.joinToString("\n")
 private fun stopWith(reason:String) {
     if(planNavigation) { navigationStop=reason; return }
     runId++; running=false; busy=false; previousSample=null; checkingMovement=false
     pendingDiagnosticBefore?.recycle(); pendingDiagnosticBefore=null
     report(reason); Toast.makeText(this,reason,Toast.LENGTH_LONG).show()
 }
 private fun resetNavigation() { resetInspection(); movementSamples.clear(); moveRetries=0; rowSteps=0; verticalNoMotion=0; direction=1; edgeCount=0; row=0; previousSample=null; checkingMovement=false; verticalCheck=false; horizontalStillChecks=0; verticalStillChecks=0; confirmedHorizontalMoves=0; attemptedHorizontalMoves=0; awaitingMotion=false; diagnosticChecks=0; diagnosticPair=0; pendingDiagnosticBefore?.recycle(); pendingDiagnosticBefore=null; diagnosticVerticalDone=false; uncertainHorizontal=0; uncertainVertical=0; verticalTransitionPending=false; edgeProbeStage=0; recoveryCycles=0; recoveryGestureCount=0; edgeEvidence=0; probeReverseChanged=false }
 private fun scan() {
     if (!running || busy) return
     busy=true
     val id=runId
     val fullRussian = searchMode==2 && TargetReading.russian(target)
     val diag = diagnosticMode
     val diagAfter = checkingMovement
     val pair=diagnosticChecks+1
     val directory=diagnosticDirectory
     val overlayMask=overlayBounds()?.let { MapText("",it.left,it.top,it.right,it.bottom) }
     val references=mutableMapOf<VisualRegistration.Frame,Boolean>()
     if(!settling || lastSettle!=null) {
         lastSettle?.let { references[it.map]=false }
         val task=current
         task?.waiting?.let { references[it.sample.map]=it.pan==null }
         task?.predictionFrame?.let { references[it.map]=false }
         if(task!=null && task.restore) {
             references[task.original.map]=true
             task.history.lastOrNull()?.let { references[it.sample.map]=true }
         }
         if(checkingMovement && (diag || (!viewInitialized && firstOverview!=null))) {
             previousSample?.let { references[it.map]=false }
         }
     }
     takeScreenshot(Display.DEFAULT_DISPLAY, executor, object:TakeScreenshotCallback {
         override fun onFailure(errorCode:Int) = postFailure(id, "Снимок недоступен: $errorCode")
         override fun onSuccess(result:ScreenshotResult) {
             val frame = try {
                 val hw = checkNotNull(Bitmap.wrapHardwareBuffer(result.hardwareBuffer,result.colorSpace))
                 try { checkNotNull(hw.copy(Bitmap.Config.ARGB_8888,false)) } finally { hw.recycle() }
             } catch(e:Exception) { postFailure(id, "Снимок: ${e.message}"); return }
             finally { result.hardwareBuffer.close() }
             try {
                 val layout=MapLayout.detect(frame.width,frame.height,frame::getPixel)
                 val texture=captureMap(frame,overlayMask,layout)
                 val measured=references.mapValues { (before,zoom)->VisualRegistration.estimate(before,texture,zoom) }
                 val sample=Snapshot(sampleMap(frame),texture,layout,measured)
                 if(diag) {
                     if(directory!=null) {
                         saveDiagnosticFrame(frame,directory,pair,if(diagAfter) "after" else "before")
                         if(diagAfter && pair<4) saveDiagnosticFrame(frame,directory,pair+1,"before")
                     }
                     val w=frame.width; val h=frame.height; frame.recycle()
                     handler.post { if(running && id==runId) {
                         width=w; height=h; busy=false; navigate(sample)
                     } }
                     return
                 }
                 val full=readLatin(frame).toMutableList()
                 if(fullRussian) full.addAll(readRussian(frame,null))
                 val lines=LevelBadges.merge(full,readLevelBadges(frame,layout) { atlas -> readLatin(atlas) })
                 handler.post {
                     if(!running || id!=runId) { frame.recycle(); return@post }
                     busy=false
                     if(originalSize!=null && originalSize != (frame.width to frame.height)) {
                         frame.recycle(); stopWith("Размер экрана изменился. Проверьте обзор."); return@post
                     }
                     width=frame.width; height=frame.height; originalSize=width to height
                     // inspectFrame returns true only when the OCR worker takes ownership of the bitmap.
                     var handedOff=false
                     try { handedOff=inspectFrame(frame,sample,lines,id) }
                     finally { if(!handedOff) frame.recycle() }
                 }
             } catch(e:Exception) { frame.recycle(); postFailure(id,"OCR: ${e.message}") }
               catch(e:LinkageError) { frame.recycle(); postFailure(id,"Русский OCR: ${e.message}") }
         }
     })
 }
 private fun postFailure(id:Long, reason:String) { handler.post { if(running && runId==id) fail(reason) } }
 private fun captureMap(frame:Bitmap,overlayMask:MapText?,layout:MapLayout):VisualRegistration.Frame {
     val (nx,ny)=VisualRegistration.gridSize(frame.width,frame.height)
     val small=Bitmap.createScaledBitmap(frame,nx,ny,true)
     val pixels=IntArray(nx*ny)
     try { small.getPixels(pixels,0,nx,0,0,nx,ny) } finally { if(small!==frame) small.recycle() }
     return VisualRegistration.capture(frame.width,frame.height,overlayMask,layout) { x,y ->
         val ix=((x+.5f)*nx/frame.width).toInt().coerceIn(0,nx-1)
         val iy=((y+.5f)*ny/frame.height).toInt().coerceIn(0,ny-1)
         val c=pixels[iy*nx+ix]
         (((c shr 16 and 255)*77 + (c shr 8 and 255)*150 + (c and 255)*29) shr 8)
     }
 }
 private fun readLatin(frame:Bitmap, area:Rect?=null):List<MapText> {
     val crop=if(area==null) frame else Bitmap.createBitmap(frame,area.left,area.top,area.width(),area.height())
     val factor=if(area!=null) 3 else if(frame.width<1200) 2 else 1
     val image=if(factor==1) crop else Bitmap.createScaledBitmap(crop,crop.width*factor,crop.height*factor,true)
     val x=area?.left ?: 0; val y=area?.top ?: 0
     fun text(s:String,b:Rect,confidence:Float)=MapText(s,x+b.left/factor,y+b.top/factor,x+(b.right+factor-1)/factor,
         y+(b.bottom+factor-1)/factor,confidence.takeIf { it>0f && it<=1f }?.times(100f))
     try {
         val result=Tasks.await(recognizer.process(InputImage.fromBitmap(image,0)))
         return buildList {
             for(block in result.textBlocks) for(line in block.lines) {
                 val whole=line.boundingBox?.let { text(line.text,it,line.confidence) }
                 if(whole!=null) add(whole)
                 if(whole==null || TargetReading.level(whole)==null) {
                     for(el in line.elements) el.boundingBox?.let {
                         val item=text(el.text,it,el.confidence)
                         if(TargetReading.level(item)!=null) add(item)
                     }
                 }
             }
         }
     } finally { if(image!==crop) image.recycle(); if(crop!==frame) crop.recycle() }
 }
 private fun readRussian(frame:Bitmap,area:Rect?)=russian.read(frame,area)
 private fun badgeMark(t:MapText)=FocusMark(TargetReading.level(t).toString(),t.x,t.y)
 private fun nameMark(t:MapText)=FocusMark("@name",t.x,t.y)
 private fun overlayBounds():Rect? {
     val view=overlay ?: return null
     if(!view.isShown) return null
     val xy=IntArray(2); view.getLocationOnScreen(xy)
     return Rect(xy[0],xy[1],xy[0]+view.width,xy[1]+view.height)
 }
 private fun sameSpot(mark:FocusMark, marks:List<FocusMark>, tolerance:Float):FocusMark? =
     marks.singleOrNull { it.label==mark.label && hypot(it.x-mark.x,it.y-mark.y)<tolerance }
 private fun inspectFrame(frame:Bitmap,sample:Snapshot,lines:List<MapText>,id:Long):Boolean {
     if(settling) {
         val previous=lastSettle
         val motion=previous?.let { sample.measured[it.map] }
         val stable=previous!=null && (motion?.let { cameraAligned(it,width,height) }
             ?: (difference(previous.colors,sample.colors)<=.025))
         if(!stable) {
             lastSettle=sample
             if(++settleFrames>=4) stopWith("Карта продолжает двигаться. Положение цели не подтверждено.")
             else schedule(550)
             return false
         }
         settling=false; lastSettle=null
     }
     val overlayBox=overlayBounds()
     val overview=sample.layout==MapLayout.OVERVIEW
     val minY=height*(if(overview) .03f else .09f)
     val maxY=height*(if(overview) .985f else .92f)
     val mask=overlayBox?.let { MapText("",it.left,it.top,it.right,it.bottom) }
     val scene=FocusTracker(width,height,FocusMark("",0f,0f),emptyList(),mask,layout=sample.layout)
     val visible=lines.filter { t-> t.right>t.left && t.bottom>t.top &&
         t.y in minY..maxY && t.x in width*.015f..width*.985f &&
         (!overview || scene.readableBox(t.x.toInt()-1,t.y.toInt()-1,t.x.toInt()+1,t.y.toInt()+1)) &&
         (overlayBox==null || !Rect.intersects(overlayBox,Rect(t.left,t.top,t.right,t.bottom))) }
     val badges=mutableListOf<MapText>()
     val nicks=mutableListOf<MapText>()
     for(t in visible) {
         val level=TargetReading.level(t)
         val group=when { level!=null -> badges; TargetReading.matches(t.text,target) -> nicks; else->continue }
         if(group.none { abs(it.x-t.x)<maxOf(3f,minOf(it.h,t.h)*.45f) && abs(it.y-t.y)<maxOf(3f,minOf(it.h,t.h)*.45f) }) group.add(t)
     }
     val marks=badges.map(::badgeMark) + if(searchMode==2) nicks.map(::nameMark) else emptyList()
     sample.landmarks=badges.filter { scene.readableBox(it.left,it.top,it.right,it.bottom) }.map(::badgeMark)
     if(!viewInitialized) {
         val candidates=if(searchMode==2) nicks.map(::nameMark) + badges.map(::badgeMark)
                        else badges.filter { TargetReading.level(it)==targetLevel }.map(::badgeMark)
         val first=firstOverview
         if(first==null) {
             firstOverview=candidates; schedule(550); return false
         }
         firstOverview=null
         planNavigation=true
         try { navigate(sample) } finally { planNavigation=false }
         if(allowedKinds.isNotEmpty() && ignoreKinds.size<6)
             mergeOverviewCandidates(first,candidates,width*.012f).forEach { queue.addLast(PendingBase(it)) }
         viewInitialized=true
     }
     if(current==null) {
         while(queue.isNotEmpty() && current==null) {
             val base=queue.removeFirst()
             val anchor=sameSpot(base.mark,marks,width*.018f)
             if(anchor==null) {
                 if(base.retry==0) {
                     queue.addLast(base.copy(retry=1)); schedule(550); return false
                 }
                 undecided++; continue
             }
             current=Inspection(base,FocusTracker(width,height,anchor,marks,mask,layout=sample.layout),sample)
             report("Кандидат ${anchor.label} · (${anchor.x.toInt()}, ${anchor.y.toInt()}) · осталось ${queue.size}")
         }
         if(current==null) {
             if(undecided>0) {
                 totalUnresolved+=undecided
                 report("На участке осталось неподтверждённых: $undecided · всего за проход: $totalUnresolved")
             }
             navigationStop?.let { stopWith(it); return false }
             val swipe=nextSwipe
             if(swipe==null) { stopWith("Для полосы не задан следующий жест"); return false }
             nextSwipe=null; viewInitialized=false; previousSample=sample; undecided=0
             performSwipe(swipe.x1,swipe.y1,swipe.x2,swipe.y2); return false
         }
     }
     val task=checkNotNull(current)
     task.tracker.layout=sample.layout; task.tracker.overlay=mask
     if(task.restore) { restoreFrame(task,marks,sample); return false }
     val pending=task.waiting
     if(pending!=null) {
         task.waiting=null
         val visual=sample.measured[pending.sample.map]
         val ok=if(pending.pan!=null) task.tracker.afterPan(marks,pending.pan,visual)
                else task.tracker.afterZoom(marks,pending.pivot,pending.factor,visual)
         if(visual!=null) {
             val moved=visual.map(pending.pivot)
             val dx=moved.x-pending.pivot.x; val dy=moved.y-pending.pivot.y
             report("Карта: ×${"%.2f".format(Locale.US,visual.scale)}, сдвиг (${dx.toInt()}, ${dy.toInt()}) · значков ${badges.size}")
             val pan=pending.pan
             if(ok && pan!=null) task.panGain=((dx*pan.x+dy*pan.y)/(pan.x*pan.x+pan.y*pan.y)).coerceIn(.35f,3f)
         }
         if(!ok) {
             if(!task.rechecked && (visual==null || !cameraAligned(visual,width,height))) {
                 task.rechecked=true; task.tracker.retryUnmatchedFrame(); task.waiting=pending
                 report("Повторный снимок после жеста · значков ${badges.size}")
                 schedule(550); return false
             }
             if(sameCamera(pending.sample,sample,visual)) task.history.removeAt(task.history.lastIndex)
             beginReturn(task,true,"Цель не сопоставлена после жеста · значков ${badges.size}, карта ${if(visual==null) "не измерена" else "измерена"}")
             restoreFrame(task,marks,sample); return false
         }
         if(task.tracker.predicted) task.predictionFrame=sample
     }
     val point=task.tracker.point
     val observed=sameSpot(FocusMark(task.base.mark.label,point.x,point.y),marks,width*.023f)
     if(task.tracker.predicted) {
         val reference=task.predictionFrame
         val stationary=reference===sample || (reference!=null && sample.measured[reference.map]?.let { cameraAligned(it,width,height) }==true)
         if(searchMode==1 || !stationary || ++task.reads>4) {
             beginReturn(task,true,"Значок после увеличения не подтверждён")
             restoreFrame(task,marks,sample); return false
         }
         return readFocus(frame,task,id) { roi ->
             val blocked=overlayBounds()
             val visibleRoi=roi.filter { blocked==null || !Rect.intersects(blocked,Rect(it.left,it.top,it.right,it.bottom)) }
             val match=task.tracker.predictedName(visibleRoi,target,badges)
             if(match!=null) noteMatch(task)
             else { task.hits=0; task.lastName=null; schedule(550) }
         }
     }
     if(observed==null) {
         beginReturn(task,true,"Значок пропал или совпал с соседом")
         restoreFrame(task,marks,sample); return false
     }
     val badge=badges.firstOrNull { badgeMark(it)==observed }
     val label=nicks.firstOrNull { nameMark(it)==observed }
     fun safe(t:MapText)=task.tracker.readableBox(t.left,t.top,t.right,t.bottom)
     fun namedBase(lines:List<MapText>)=badge!=null && lines.any {
         safe(it) && TargetReading.isBaseCaption(badge,it,badges,width,height)
     }
     // Retain the direct-match path for a fully readable base before moving the camera.
     val direct=when(searchMode) {
         1 -> badge!=null && safe(badge) && (BaseMarker.hasOverviewPin(badge,width,height,frame::getPixel) ||
                  (!overview && namedBase(visible)))
         2 -> label!=null && safe(label) || badge!=null && safe(badge) &&
             nicks.any { safe(it) && TargetReading.ownsName(badge,it,badges,width,height) }
         else -> badge!=null && safe(badge) && nicks.any { safe(it) && TargetReading.ownsName(badge,it,badges,width,height) }
     }
     if(++task.reads>8) {
         beginReturn(task,true,"Чтение не стабилизировалось"); restoreFrame(task,marks,sample); return false
     }
     if(direct) { noteMatch(task); return false }
     if(badge!=null && safe(badge) && searchMode!=1) {
         return readFocus(frame,task,id) { roi ->
             decideReading(task,badge,badges,roi,sample,marks,overlayBounds())
         }
     }
     if(!task.tracker.inReadingArea() || (badge!=null && !safe(badge))) {
         task.hits=0; task.lastName=null
         val delta=task.tracker.nextPan(forceCenter=badge!=null && !safe(badge))
         if(delta==null) { beginReturn(task,true,"Не удалось вывести подпись из-под интерфейса"); restoreFrame(task,marks,sample) }
         else issueFocusStep(task,CameraStep(delta,point,sample))
         return false
     }
     if(searchMode==1) {
         return readFocus(frame,task,id) { roi ->
             if(!overview && namedBase(roi)) noteMatch(task)
             else {
                 task.hits=0; task.lastName=null
                 when {
                     task.reads<2 -> schedule(550)
                     scaleMode!=0 && task.zooms==0 -> {
                         task.zooms++
                         issueFocusStep(task,CameraStep(null,point,sample,if(task.base.retry==0) PINCH_FACTOR else 1.24f))
                     }
                     else -> { beginReturn(task,true,"Уровень есть, тип объекта не подтверждён"); restoreFrame(task,marks,sample) }
                 }
             }
         }
     }
     decideReading(task,badge,badges,visible,sample,marks,overlayBox)
     return false
 }
 private fun readFocus(frame:Bitmap,task:Inspection,id:Long,completed:(List<MapText>)->Unit):Boolean {
     val point=task.tracker.point
     val area=Rect((point.x-width*.25f).toInt(),(point.y-height*.08f).toInt(),
                   (point.x+width*.25f).toInt(),(point.y+height*.06f).toInt())
     check(area.intersect(0,0,frame.width,frame.height))
     busy=true
     executor.execute {
         try {
             val lines=if(TargetReading.russian(target)) readRussian(frame,area) else readLatin(frame,area)
             val complete=lines.filter { it.left>area.left+1 && it.top>area.top+1 &&
                                         it.right<area.right-1 && it.bottom<area.bottom-1 }
             handler.post { if(running && id==runId && current===task) { busy=false; completed(complete) } }
         } catch(e:Exception) { postFailure(id,"OCR подписи: ${e.message}") }
           catch(e:LinkageError) { postFailure(id,"OCR подписи: ${e.message}") }
         finally { frame.recycle() }
     }
     return true
 }
 private fun noteMatch(task:Inspection) {
     if(task.lastName!=target) task.hits=0
     task.lastName=target
     if(++task.hits>=2) found() else schedule(550)
 }
 private fun decideReading(task:Inspection,badge:MapText?,badges:List<MapText>,lines:List<MapText>,
                           sample:Snapshot,marks:List<FocusMark>,overlayBox:Rect?) {
     val nearby=lines.filter { t-> badge!=null && t.text.any(Char::isLetter) && TargetReading.level(t)==null &&
         TargetReading.ownsName(badge,t,badges,width,height) }
     val labels=nearby.filter { t->task.tracker.readableBox(t.left,t.top,t.right,t.bottom) &&
         (overlayBox==null || !Rect.intersects(overlayBox,Rect(t.left,t.top,t.right,t.bottom))) }
     if(labels.any { TargetReading.matches(it.text,target) }) { noteMatch(task); return }
     if(nearby.size!=labels.size || !task.tracker.inReadingArea()) {
         val delta=task.tracker.nextPan(forceCenter=nearby.size!=labels.size)
         if(delta!=null) issueFocusStep(task,CameraStep(delta,task.tracker.point,sample))
         else { beginReturn(task,true,"Подпись перекрыта интерфейсом"); restoreFrame(task,marks,sample) }
         return
     }
     val other=labels.singleOrNull()?.takeIf(TargetReading::reliableOther)?.text
     if(task.zooms>0 && other!=null && task.lastName==other) {
         beginReturn(task,false,"Прочитан другой ник"); restoreFrame(task,marks,sample); return
     }
     task.hits=0; task.lastName=other
     if(task.zooms>0 && other!=null) { schedule(550); return }
     if(scaleMode==0) {
         if(other!=null && task.reads>=2 && task.manualOther==other) {
             beginReturn(task,false,"На текущем масштабе подтверждён другой ник")
         } else if(task.reads<3) {
             task.manualOther=other; schedule(550); return
         } else {
             beginReturn(task,true,"Имя не подтверждено при ручном масштабе")
         }
         restoreFrame(task,marks,sample); return
     }
     if(task.zooms>=3) { beginReturn(task,true,"Подпись осталась нечитаемой"); restoreFrame(task,marks,sample); return }
     task.zooms++
     val factor=if(task.base.retry>0) 1.24f else PINCH_FACTOR
     issueFocusStep(task,CameraStep(null,task.tracker.point,sample,factor))
 }
 private fun issueFocusStep(task:Inspection,step:CameraStep) {
     task.hits=0; task.lastName=null; task.manualOther=null; task.reads=0; task.rechecked=false
     task.waiting=step; task.history.add(step); task.sentGesture=true
     sendCameraStep(step,false)
 }
 private fun beginReturn(task:Inspection,retry:Boolean,why:String) {
     task.retry=retry; task.restore=true; task.undoWait=0
     report("$why · возврат к обзору")
 }
 private fun sameCamera(before:Snapshot,after:Snapshot,visual:MapTransform?):Boolean {
     if(before.layout!=after.layout) return false
     if(visual!=null) return cameraAligned(visual,width,height)
     return difference(before.colors,after.colors)<=.055 &&
         matchingLandmarks(before.landmarks,after.landmarks,width,height)
 }
 private fun restoreFrame(task:Inspection,marks:List<FocusMark>,sample:Snapshot) {
     // Undo one command at a time; never chain inverses from an unverified intermediate view.
     if(task.undoing) {
         val before=task.history.last().sample
         val visual=sample.measured[before.map]
         val aligned=sameCamera(before,sample,visual)
         if(!aligned) {
             if(visual!=null && correctReturn(task,sample,visual)) return
             if(++task.undoWait<=2) schedule(550) else stopWith("Обзор после обратного жеста не подтверждён. Проверьте карту.")
             return
         }
         task.history.removeAt(task.history.lastIndex); task.undoing=false; task.undoWait=0
     }
     if(task.history.isNotEmpty()) {
         task.undoing=true; sendCameraStep(task.history.last(),true); return
     }
     val returned=sample.measured[task.original.map]
     val sameOverview=if(task.sentGesture) task.tracker.restored(marks,returned) &&
         (returned!=null || difference(task.original.colors,sample.colors)<=.045)
         else difference(task.original.colors,sample.colors)<=.045
     if(!sameOverview) {
         if(returned!=null && correctReturn(task,sample,returned)) return
         if(++task.undoWait<=2) schedule(550) else stopWith("Исходные ориентиры не совпали. Проверьте обзор.")
         return
     }
     if(task.retry) {
         if(task.base.retry==0) queue.addLast(task.base.copy(retry=1))
         else undecided++
     }
     if(returned!=null) {
         repeat(queue.size) {
             val base=queue.removeFirst()
             val p=returned.map(FocusPoint(base.mark.x,base.mark.y))
             queue.addLast(base.copy(mark=base.mark.copy(x=p.x,y=p.y)))
         }
     }
     current=null; schedule(550)
 }
 private fun correctReturn(task:Inspection,sample:Snapshot,measured:MapTransform):Boolean {
     if(task.corrections>=2) return false
     val center=task.tracker.readingCenter ?: return false
     val adjustment=returnAdjustment(measured,center,width,height,task.panGain) ?: return false
     if(scaleMode==0 && adjustment.pan==null) return false
     task.corrections++; task.undoWait=0
     report("Доводка к исходному обзору · ${task.corrections}/2")
     sendCameraStep(CameraStep(adjustment.pan,center,sample,adjustment.factor),false)
     return true
 }
 private fun sendCameraStep(step:CameraStep,inverse:Boolean) {
     val paths=if(step.pan!=null) {
         val sign=if(inverse) -1 else 1
         val dx=step.pan.x*sign; val dy=step.pan.y*sign
         val center=current?.tracker?.readingCenter ?: return stopWith("Центр наведения перекрыт")
         val cx=center.x; val cy=center.y
         report("Центрирование · сдвиг (${dx.toInt()}, ${dy.toInt()})")
         listOf(Path().apply { moveTo(cx-dx/2,cy-dy/2); lineTo(cx+dx/2,cy+dy/2) })
     } else {
         val small=width*.105f; val large=small*step.factor
         report(if(inverse) "Возвращаю масштаб" else if(step.factor>1f) "Увеличиваю на один шаг" else "Уменьшаю на один шаг")
         listOf(-1,1).map { sign->Path().apply {
             moveTo(step.pivot.x+sign*(if(inverse) large else small),step.pivot.y)
             lineTo(step.pivot.x+sign*(if(inverse) small else large),step.pivot.y)
         } }
     }
     val builder=GestureDescription.Builder()
     paths.forEach { builder.addStroke(GestureDescription.StrokeDescription(it,0,400)) }
     val id=runId
     if(!dispatchGesture(builder.build(),object:GestureResultCallback() {
         override fun onCompleted(g:GestureDescription?) {
             if(running && runId==id) {
                 settling=true; settleFrames=0; lastSettle=null
                 schedule(moveDelayMs)
             }
         }
         override fun onCancelled(g:GestureDescription?) { if(running && runId==id) fail("Жест наведения прерван") }
     },handler)) fail("Жест наведения отклонён")
 }

 private fun found() {
  running=false; runId++; report("Цель найдена: ${if(searchMode==1) "уровень $targetLevel" else target}")
  notifyFound()
  Toast.makeText(this,status,Toast.LENGTH_LONG).show()
  // Do not tap automatically: the user can inspect the result and tap manually.
 }
 // Sample a fixed central rectangle. UI and animated edge controls are excluded.
 private fun sampleMap(bitmap:Bitmap):IntArray {
  val result=IntArray(sampleCols*sampleRows)
  val x0=(bitmap.width*0.10f).toInt(); val x1=(bitmap.width*0.90f).toInt()
  val y0=(bitmap.height*0.16f).toInt(); val y1=(bitmap.height*0.84f).toInt()
  var i=0
  for(y in 0 until sampleRows) for(x in 0 until sampleCols) {
   val px=x0+(x1-x0)*(x+1)/(sampleCols+1)
   val py=y0+(y1-y0)*(y+1)/(sampleRows+1)
   result[i++]=bitmap.getPixel(px.coerceIn(0,bitmap.width-1),py.coerceIn(0,bitmap.height-1))
  }
  return result
 }
 private fun difference(a:IntArray,b:IntArray):Double {
  var sum=0L
  for(i in a.indices) {
   val c=a[i]; val d=b[i]
   sum+=abs((c shr 16 and 255)-(d shr 16 and 255))
   sum+=abs((c shr 8 and 255)-(d shr 8 and 255))
   sum+=abs((c and 255)-(d and 255))
  }
  return sum.toDouble()/(a.size*3*255)
 }
 // Compare map pixels under translation. A low whole-frame difference alone
 // is NOT proof of a border. UI animations and featureless terrain are ambiguous.
 private data class Motion(val kind:String,val dx:Int=0,val dy:Int=0,val score:Double=0.0,val baseline:Double=0.0)
 private fun colorDistance(a:Int,b:Int):Double {
  val r=abs((a shr 16 and 255)-(b shr 16 and 255))
  val g=abs((a shr 8 and 255)-(b shr 8 and 255))
  val bl=abs((a and 255)-(b and 255))
  return (r+g+bl)/765.0
 }
 private fun translatedDifference(a:IntArray,b:IntArray,dx:Int,dy:Int):Double {
  val marginX=3; val marginY=3
  val x0=maxOf(marginX,-dx); val x1=minOf(sampleCols-marginX,sampleCols-dx)
  val y0=maxOf(marginY,-dy); val y1=minOf(sampleRows-marginY,sampleRows-dy)
  if(x1-x0<9 || y1-y0<9) return 1.0
  var sum=0.0; var count=0
  for(y in y0 until y1) for(x in x0 until x1) {
   sum+=colorDistance(a[y*sampleCols+x],b[(y+dy)*sampleCols+x+dx]); count++
  }
  return sum/count
 }
 // A sparse sample cannot always establish translation of a large camera swipe.
 // We distinguish 'changed' from a reliably matched translation; neither is an edge.
 private fun measureMotion(before:IntArray,after:IntArray,vertical:Boolean):Motion {
  val baseline=translatedDifference(before,after,0,0)
  if(baseline<0.010) return Motion("still",baseline=baseline)
  var best=1.0; var bestShift=0
  val limit=if(vertical) 58 else 54
  for(shift in -limit..limit step 2) {
   if(abs(shift)<2) continue
   val score=if(vertical) translatedDifference(before,after,0,shift)
             else translatedDifference(before,after,shift,0)
   if(score<best) { best=score; bestShift=shift }
  }
  if(bestShift!=0 && best<baseline*0.82 && best<0.16)
   return Motion("moved",if(vertical) 0 else bestShift,if(vertical) bestShift else 0,best,baseline)
  // A nonzero difference is not proof of movement: animations and HUD can change.
  // Nor is it proof of a stationary camera. Mark it explicitly as uncertain.
  return Motion("uncertain",score=best,baseline=baseline)
 }
 private fun startNextRow(sample:Snapshot,reason:String) {
  report(reason)
  edgeProbeStage=0; edgeEvidence=0; probeReverseChanged=false
  previousSample=sample; checkingMovement=true; verticalCheck=true
  uncertainVertical=0; verticalStillChecks=0; verticalTransitionPending=true
  report("Один вертикальный шаг на 22% экрана; после него смена направления")
  performSwipe(.50f,.61f,.50f,.39f)
 }
 private fun navigate(sample:Snapshot) {
  if(!running) return
  if(checkingMovement && previousSample!=null) {
   val wasProbe=edgeProbeStage
   val wasVertical=verticalCheck
   val before=checkNotNull(previousSample)
   val registered=sample.measured[before.map]
   val m=if(registered!=null) {
       val c=FocusPoint(width*.5f,height*.5f); val p=registered.map(c)
       val dx=p.x-c.x; val dy=p.y-c.y
       val swipe=dispatchedSwipe
       val along=if(wasVertical) dy else dx; val cross=if(wasVertical) dx else dy
       val expected=if(swipe==null) 0f else if(wasVertical) swipe.y2-swipe.y1 else swipe.x2-swipe.x1
       val kind=when {
           cameraAligned(registered,width,height) -> "still"
           abs(registered.scale-1f)<.06f && along*expected>0 && abs(along)>width*.018f &&
               abs(cross)<maxOf(width*.025f,abs(along)*.42f) -> "moved"
           else -> "uncertain"
       }
       Motion(kind,dx.roundToInt(),dy.roundToInt(),baseline=difference(before.colors,sample.colors))
   } else measureMotion(before.colors,sample.colors,wasVertical)
   checkingMovement=false; previousSample=null; verticalCheck=false
   val detail=if(registered==null) "отсчётов, разница ${"%.3f".format(Locale.US,m.baseline)}" else "px, ориентиров ${registered.support}"
   report("${if(wasVertical) "Вертикаль" else "Горизонталь"}: ${m.kind} · сдвиг (${m.dx},${m.dy}) $detail")
   if(!diagnosticMode && wasProbe==3) {
    if(m.kind=="moved") {
     report("Контроль неопределённого движения: обратный жест работает; возвращаю положение")
     edgeProbeStage=4; previousSample=sample; checkingMovement=true; verticalCheck=false
     swipeAlongRow(direction)
    } else if(m.kind=="uncertain" && m.baseline>=0.025 && confirmedHorizontalMoves>0) {
     probeReverseChanged=true
     report("Обратный жест изменил кадр, направление не измерено; проверяю возврат")
     edgeProbeStage=4; previousSample=sample; checkingMovement=true; verticalCheck=false
     swipeAlongRow(direction)
    } else {
     edgeProbeStage=0
     uncertainHorizontal=0
     report("Обратный контроль не определил сдвиг; выполняю укороченный горизонтальный свайп, край НЕ подтверждён")
     previousSample=sample; checkingMovement=true; verticalCheck=false
     recoveryGestureCount++
     swipeAlongRowShort(direction)
    }
    return
   }
   if(!diagnosticMode && wasProbe==4) {
    edgeProbeStage=0
    if(probeReverseChanged && m.baseline>=0.025 && m.kind!="still") {
     edgeEvidence++
     report("Пара обратный/возвратный жест изменила кадры; косвенное подтверждение края $edgeEvidence/2")
     probeReverseChanged=false
     if(edgeEvidence>=2 && confirmedHorizontalMoves>0) {
      startNextRow(sample,"Предполагаемый край по двум контрольным парам; перехожу на следующую полосу (проверка положения приблизительная)")
      return
     }
    } else if(m.kind=="moved") {
     edgeEvidence=0
    }
    probeReverseChanged=false
    if(m.kind!="moved") {
     report("Возврат не измерен: продолжаю проверку укороченным свайпом; положение приблизительное")
     uncertainHorizontal=0
     previousSample=sample; checkingMovement=true; verticalCheck=false
     recoveryGestureCount++
     swipeAlongRowShort(direction)
     return
    }
    uncertainHorizontal=0
    report("Контроль восстановлен; продолжаю текущую полосу без перехода вниз")
    // Continue with a new horizontal gesture below.
   }
   if(!diagnosticMode && wasProbe==1) {
    if(m.kind=="moved" || (m.kind=="uncertain" && m.baseline>=0.025)) {
     probeReverseChanged=m.kind=="uncertain"
     report("Контрольный обратный свайп изменил карту; возвращаюсь к предполагаемой границе")
     edgeProbeStage=2; previousSample=sample; checkingMovement=true; verticalCheck=false
     swipeAlongRow(direction)
    } else {
     edgeProbeStage=0
     report("Обратный свайп не распознан: край НЕ подтверждён; пробую укороченный жест по полосе")
     uncertainHorizontal=0; horizontalStillChecks=0
     previousSample=sample; checkingMovement=true; verticalCheck=false
     recoveryGestureCount++
     swipeAlongRowShort(direction)
    }
    return
   }
   if(!diagnosticMode && wasProbe==2) {
    edgeProbeStage=0
    if(m.kind!="moved" && !(probeReverseChanged && m.kind=="uncertain" && m.baseline>=0.025 && confirmedHorizontalMoves>0)) {
     report("Возврат к границе не измерен: переход вниз отменён; пробую укороченный горизонтальный свайп")
     uncertainHorizontal=0; horizontalStillChecks=0
     previousSample=sample; checkingMovement=true; verticalCheck=false
     recoveryGestureCount++
     swipeAlongRowShort(direction)
     return
    }
    startNextRow(sample,if(m.kind=="moved") "Горизонтальная граница подтверждена; перехожу вниз" else "Граница подтверждена косвенно: контрольные жесты изменили кадры; перехожу вниз")
    return
   }
   if(diagnosticMode) {
    diagnosticChecks++
    if(wasVertical) {
     diagnosticVerticalDone=true
     stopWith("Диагностика завершена: 3+1; изображения сохранены в папке приложения")
     return
    }
    if(diagnosticChecks>=3) {
     report("Диагностика: проверяю вертикальный свайп")
     previousSample=sample; checkingMovement=true; verticalCheck=true
     performSwipe(.50f,.78f,.50f,.28f)
     return
    }
   } else if(wasVertical) {
    // One row transition must never generate multiple vertical gestures.
    // Additional screenshots cannot prove translation after the initial swipe.
    if(!verticalTransitionPending) {
     stopWith("Ошибка навигации: нет ожидаемого вертикального перехода")
     return
    }
    verticalTransitionPending=false
    if(m.kind=="still" || (m.kind=="uncertain" && m.baseline<0.020)) {
     stopWith("Вертикальный шаг не подтверждён; повторный свайп вниз не выполняю")
     return
    }
    if(m.kind=="uncertain") {
     report("ВНИМАНИЕ: вертикальный шаг предположительный (изображение изменилось, сдвиг не измерен); повторного свайпа вниз не будет")
    } else {
     report("Вертикальный шаг подтверждён: ${m.dy} отсчётов")
    }
    uncertainVertical=0; verticalStillChecks=0
    direction=-direction; row++; rowSteps=0; horizontalStillChecks=0
    uncertainHorizontal=0; confirmedHorizontalMoves=0; recoveryCycles=0
    report("Полоса ${row+1}: направление ${if(direction==1) "вправо" else "влево"}; вертикальных свайпов: 1")
    if(row>=maxRows) { stopWith("Лимит полос: полный обход не подтверждён"); return }
   } else {
    when(m.kind) {
     "moved" -> { rowSteps++; confirmedHorizontalMoves++; uncertainHorizontal=0; horizontalStillChecks=0; recoveryCycles=0; recoveryGestureCount=0; edgeEvidence=0 }
     "uncertain" -> {
      uncertainHorizontal++
      if(uncertainHorizontal>=4) {
       recoveryCycles++
       if(recoveryCycles>6 || recoveryGestureCount>12) {
        stopWith("Навигация приостановлена: 6 циклов и реальные восстановительные жесты не позволили определить положение; переход вниз запрещён")
        return
       }
       report("Неопределённое движение 4/4: контроль обратным жестом (цикл $recoveryCycles), пока НЕ переход вниз")
       edgeProbeStage=3; probeReverseChanged=false; previousSample=sample; checkingMovement=true; verticalCheck=false
       swipeAlongRow(-direction)
       return
      }
      report("Неясное движение $uncertainHorizontal/4: продолжаю проверку следующим жестом")
      horizontalStillChecks=0
     }
     else -> {
      uncertainHorizontal=0
      horizontalStillChecks++
      if(horizontalStillChecks>=maxMoveRetries) {
       report("Предполагаемый горизонтальный край: проверяю обратным свайпом")
       edgeProbeStage=1; previousSample=sample; checkingMovement=true; verticalCheck=false
       swipeAlongRow(-direction)
       return
      }
     }
    }
   }
  }
  if(!diagnosticMode && rowSteps>=maxRowSteps) {
   stopWith("Ошибка навигации: лимит шагов; край не подтверждён")
   return
  }
  report(if(diagnosticMode) "Диагностика: горизонтальный тест ${diagnosticChecks+1}/3" else "Сканирую: полоса ${row+1}, подтверждено $rowSteps, ${if(direction==1) "вправо" else "влево"}${if(totalUnresolved>0) " · неподтверждённых $totalUnresolved" else ""}")
  previousSample=sample; checkingMovement=true; verticalCheck=false
  attemptedHorizontalMoves++
  swipeAlongRow(direction)
 }
 private fun swipeAlongRowShort(mapDirection:Int) {
  // Smaller gesture gives image matching a better chance to track landmarks.
  if(mapDirection==1) performSwipe(.60f,.52f,.40f,.52f)
  else performSwipe(.40f,.52f,.60f,.52f)
 }
 private fun swipeAlongRow(mapDirection:Int) {
  if(mapDirection==1) performSwipe(.72f,.52f,.28f,.52f)
  else performSwipe(.28f,.52f,.72f,.52f)
 }
 private fun performSwipe(x1:Float,y1:Float,x2:Float,y2:Float) {
  if(!running) return
  if(planNavigation) { nextSwipe=Swipe(x1,y1,x2,y2); return }
  dispatchedSwipe=Swipe(x1,y1,x2,y2)
  val id=runId
  val path=Path().apply { moveTo(width*x1,height*y1); lineTo(width*x2,height*y2) }
  val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,500)).build()
  report("Жест: отправка (${"%.2f".format(Locale.US,x1)},${"%.2f".format(Locale.US,y1)}) → (${"%.2f".format(Locale.US,x2)},${"%.2f".format(Locale.US,y2)})")
  val accepted=dispatchGesture(gesture,object:GestureResultCallback() {
   override fun onCompleted(gestureDescription:GestureDescription?) {
    if(running && id==runId) { report("Жест: выполнен системой"); schedule(moveDelayMs) }
   }
   override fun onCancelled(gestureDescription:GestureDescription?) { if(running && id==runId) fail("Жест прерван системой") }
  },handler)
  if(!accepted) fail("Жест не принят системой")
 }
 private fun notifyFound() {
  try {
   tone?.release()
   tone=ToneGenerator(AudioManager.STREAM_ALARM, 95)
   tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
   handler.postDelayed({ tone?.release(); tone=null }, 1800)
  } catch (_:Exception) { }
  try {
   val vibrator=getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
   val pattern=longArrayOf(0,300,150,300,150,650)
   if (Build.VERSION.SDK_INT>=26) vibrator.vibrate(VibrationEffect.createWaveform(pattern,-1))
   else @Suppress("DEPRECATION") vibrator.vibrate(pattern,-1)
  } catch (_:Exception) { }
 }
 private fun fail(reason:String) { stopWith("Ошибка поиска: $reason") }
 private fun updateOverlay() { overlay?.text=if(running) "◌ Сканирую" else if(status.startsWith("Цель найдена")) "✓ Цель найдена" else if(status.startsWith("Ошибка")) "! Ошибка поиска" else "▶ Старт" }
 private fun hideOverlay() { try { overlay?.let { windowManager?.removeView(it) } } catch (_:Exception) {} ; overlay=null }
 private fun showOverlay() {
  if(overlay!=null) return
  try {
   windowManager=getSystemService(Context.WINDOW_SERVICE) as WindowManager
   val view=TextView(this).apply {
    text="▶ Старт"; textSize=15f; setTextColor(Color.WHITE)
    background=GradientDrawable().apply { cornerRadius=18f*resources.displayMetrics.density; setColor(Color.argb(194,25,85,110)) }; setPadding(22,16,22,16)
   }
   val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
    android.graphics.PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=12; y=220 }
   var startX=0f; var startY=0f; var initialX=0; var initialY=0; var moved=false
   view.setOnTouchListener { _,event ->
    when(event.actionMasked) {
     MotionEvent.ACTION_DOWN -> { startX=event.rawX; startY=event.rawY; initialX=params.x; initialY=params.y; moved=false; true }
     MotionEvent.ACTION_MOVE -> { val dx=event.rawX-startX; val dy=event.rawY-startY; if(abs(dx)>12 || abs(dy)>12) moved=true; if(moved) { params.x=initialX+dx.toInt(); params.y=initialY+dy.toInt(); windowManager?.updateViewLayout(view,params) }; true }
     MotionEvent.ACTION_UP -> { if(!moved) { if(running) stopScanning() else startScanning() }; true }
     else -> false
    }
   }
   windowManager?.addView(view,params); overlay=view
  } catch (_:Exception) { overlay=null }
 }

}
