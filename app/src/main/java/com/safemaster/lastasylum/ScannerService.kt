package com.safemaster.lastasylum

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.RectF
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

class ScannerService : AccessibilityService() {
 companion object { var instance:ScannerService?=null; private set }
 private val handler=Handler(Looper.getMainLooper())
 private val executor=Executors.newSingleThreadExecutor()
 private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
 private var ocrFrames=0
 private var moveDelayMs=2500L
 private var scaleMode=0 // 0 manual, 1 assisted (no unsafe unverified zoom)
 private var ignoreKinds=setOf<String>()
 private var scanStart=0L
 private var lastLog=""
 private val journal=ArrayDeque<String>()
 private var rowSteps=0
 private var maxRowSteps=90
 private var verticalNoMotion=0
 private var maxMoveRetries=5
 private var moveRetries=0
 private var restoreRetries=0
 private var baselineSample:IntArray?=null
 private var zoomCenterX=0f
 private var zoomCenterY=0f
 private data class Candidate(val x:Float,val y:Float,val name:String,val level:Int,val kind:Int)
 private var pendingCandidates=mutableListOf<Candidate>()
 private var verificationAttempts=0
 private var lastCandidateAnchorX=0f
 private var lastCandidateAnchorY=0f
 private var allowedKinds=setOf(0,1,2) // 0 blue, 1 red, 2 white
 private var zoomStep=0
 private val maxZoomSteps=3
 private var centered=false
 private var savedCandidate:Candidate?=null
 private var zoomScale=1f
 private var pendingGesture=false
 private var uncertainMoves=0
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
 private var phase=0 // 0 search, 1 zoom verification, 2 restore zoom
 private var candidateX=0f
 private var candidateY=0f
 private var candidateKey=""
 private val rejected=mutableSetOf<String>()
 private var noMotion=0
 private var zoomHits=0
 private var hits=0
 private var step=0
 private var direction=1 // 1: right on map, -1: left on map
 private var edgeCount=0
 private var row=0
 private var previousSample:IntArray?=null
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
 override fun onServiceConnected() { instance=this; status="Служба готова"; showOverlay() }
 override fun onAccessibilityEvent(event:AccessibilityEvent?) {}
 override fun onInterrupt() { stopScanning() }
 override fun onDestroy() { hideOverlay(); stopScanning(); recognizer.close(); tone?.release(); executor.shutdown(); instance=null; super.onDestroy() }
 fun configure(name:String, level:Int, mode:Int=0, delayMs:Long=2500, retries:Int=5, kinds:Set<Int> = setOf(0,1,2), scale:Int=0, ignored:Set<String> = emptySet(), diagnostic:Boolean=false) { diagnosticMode=diagnostic; scaleMode=scale; ignoreKinds=ignored; target=name.trim(); targetLevel=level; searchMode=mode.coerceIn(0,2); moveDelayMs=delayMs.coerceIn(500,15000); maxMoveRetries=retries.coerceIn(1,12); allowedKinds=kinds; hits=0; rejected.clear(); resetNavigation() }
 fun startScanning() { if(running) return; hits=0; resetNavigation(); scanStart=System.currentTimeMillis(); running=true; busy=false
  diagnosticDirectory=if(diagnosticMode) File(getExternalFilesDir(null) ?: filesDir,"navigation_${scanStart}").also { it.mkdirs() } else null
  report(if(diagnosticMode) "Диагностика 3+1; кадры: ${diagnosticDirectory?.absolutePath}" else "Сканирую: ${if(scaleMode==0) "ручной" else "контроль масштаба"}")
  handler.postDelayed({ scan() },1200) }
 private fun saveDiagnosticFrame(bitmap:Bitmap, suffix:String) {
  if(!diagnosticMode) return
  val folder=diagnosticDirectory ?: return
  try { FileOutputStream(File(folder,"pair_${diagnosticPair}_${suffix}.jpg")).use { bitmap.compress(Bitmap.CompressFormat.JPEG,88,it) } }
  catch(e:Exception) { report("Не удалось сохранить кадр: ${e.message}") }
 }
 fun stopScanning() { running=false; busy=false; previousSample=null; pendingDiagnosticBefore?.recycle(); pendingDiagnosticBefore=null; report("Остановлено пользователем") }
 private fun report(message:String) { status=message; lastLog=message; val time=SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date()); journal.addLast("$time | $message"); while(journal.size>120) journal.removeFirst(); updateOverlay() }
 fun diagnostics():String = journal.joinToString("\n")
 private fun stopWith(reason:String) { running=false; busy=false; previousSample=null; checkingMovement=false; pendingDiagnosticBefore?.recycle(); pendingDiagnosticBefore=null; report(reason); Toast.makeText(this,reason,Toast.LENGTH_LONG).show() }
 private fun resetNavigation() { pendingCandidates.clear(); verificationAttempts=0; savedCandidate=null; zoomStep=0; centered=false; zoomScale=1f; pendingGesture=false; uncertainMoves=0; movementSamples.clear(); baselineSample=null; restoreRetries=0; moveRetries=0; phase=0; noMotion=0; zoomHits=0; step=0; rowSteps=0; verticalNoMotion=0; direction=1; edgeCount=0; row=0; previousSample=null; checkingMovement=false; verticalCheck=false; horizontalStillChecks=0; verticalStillChecks=0; confirmedHorizontalMoves=0; attemptedHorizontalMoves=0; awaitingMotion=false; diagnosticChecks=0; diagnosticPair=0; pendingDiagnosticBefore?.recycle(); pendingDiagnosticBefore=null; diagnosticVerticalDone=false; uncertainHorizontal=0; uncertainVertical=0; verticalTransitionPending=false; edgeProbeStage=0; recoveryCycles=0; recoveryGestureCount=0; edgeEvidence=0; probeReverseChanged=false }
 private fun scan() {
  if(!running || busy) return
  busy=true
  takeScreenshot(Display.DEFAULT_DISPLAY,executor,object:TakeScreenshotCallback {
   override fun onSuccess(result:ScreenshotResult) {
    val buffer:HardwareBuffer=result.hardwareBuffer
    try {
     val hardware=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)
     val bitmap=hardware?.copy(Bitmap.Config.ARGB_8888,false)
     hardware?.recycle()
     if(bitmap==null) { handler.post { fail("Ошибка изображения") }; return }
     width=bitmap.width; height=bitmap.height
     val sample=sampleMap(bitmap)
     if(diagnosticMode && phase==0) {
      // Save the full image at the time of the gesture, not an interpolated sparse sample.
      if(checkingMovement) saveDiagnosticFrame(bitmap,"after")
      else { diagnosticPair++; saveDiagnosticFrame(bitmap,"before") }
      handler.post {
       bitmap.recycle(); busy=false
       if(running) navigate(sample)
      }
      return
     }
     // Enlarging the map improves small Latin labels and level digits. Keep
     // bounding boxes in original screen coordinates for gesture positioning.
     ocrFrames++
     val ocrFactor=if(bitmap.width<1200) 2 else 1
     val ocrBitmap=if(ocrFactor==1) bitmap else Bitmap.createScaledBitmap(bitmap,bitmap.width*ocrFactor,bitmap.height*ocrFactor,true)
     recognizer.process(InputImage.fromBitmap(ocrBitmap,0))
      .addOnSuccessListener { text ->
       val blocks=text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
        line.boundingBox?.let { b -> Pair(line.text,Rect(b.left/ocrFactor,b.top/ocrFactor,b.right/ocrFactor,b.bottom/ocrFactor)) }
       }
       handler.post {
        if(ocrBitmap!==bitmap) ocrBitmap.recycle()
        bitmap.recycle(); busy=false
        if(!running) return@post
        when(phase) {
         0 -> {
          if(pendingCandidates.isEmpty()) {
           pendingCandidates.addAll(findCandidates(blocks).take(24))
           if(pendingCandidates.isNotEmpty()) report("Кандидатов на текущем экране: ${pendingCandidates.size}")
          }
          if(pendingCandidates.isNotEmpty()) {
           val candidate=pendingCandidates.removeAt(0)
           candidateX=candidate.x; candidateY=candidate.y; savedCandidate=candidate
           lastCandidateAnchorX=candidate.x; lastCandidateAnchorY=candidate.y
           candidateKey="${row}:${direction}:${step}:${(candidateX/55).toInt()}:${(candidateY/55).toInt()}"
           if(searchMode==1 && candidate.level==targetLevel) found()
           else if(searchMode==2 && namesMatch(candidate.name,target) && candidate.level==targetLevel) found()
           else if(searchMode==0 && namesMatch(candidate.name,target) && candidate.level==targetLevel) found()
           else if(searchMode==2) { rejected.add(candidateKey); handler.postDelayed({scan()},250) }
           else {
            baselineSample=sample.copyOf(); restoreRetries=0; zoomStep=0; centered=true; verificationAttempts=0
            // Any zoom invalidates other candidates' screen coordinates. Re-detect on return.
            pendingCandidates.clear()
            phase=1; zoomHits=0; status="Проверяю кандидата без перемещения карты"
            performPinch(candidateX,candidateY,true)
           }
          } else navigate(sample)
         }
         1 -> {
          verificationAttempts++
          // Keep the zoom anchor fixed; never chase OCR boxes across a busy map.
          val matched=hasNameNear(blocks,lastCandidateAnchorX,lastCandidateAnchorY) &&
           hasExpectedLevelNear(blocks,lastCandidateAnchorX,lastCandidateAnchorY)
          if(matched) {
           zoomHits++
           if(zoomHits>=2) found() else handler.postDelayed({scan()},500)
          } else if(zoomStep<maxZoomSteps && verificationAttempts<maxZoomSteps+3) {
           zoomHits=0
           performPinch(lastCandidateAnchorX,lastCandidateAnchorY,true)
          } else {
           rejected.add(candidateKey); phase=2; status="Кандидат не подтверждён; возвращаю обзор"
           if(zoomStep>0) performPinch(lastCandidateAnchorX,lastCandidateAnchorY,false)
           else handler.postDelayed({scan()},250)
          }
         }
         else -> {
          if(zoomStep>0) {
           status="Возврат масштаба: осталось $zoomStep"
           performPinch(lastCandidateAnchorX,lastCandidateAnchorY,false)
          } else {
           pendingCandidates.clear()
           phase=0; baselineSample=null; previousSample=null; checkingMovement=false
           // The original camera offset may differ after pinch; stop rather than silently drift.
           status="Обзор восстановлен; повторное обнаружение объектов"
           handler.postDelayed({scan()},moveDelayMs)
          }
         }
        }
       }
      }.addOnFailureListener { e -> if(ocrBitmap!==bitmap) ocrBitmap.recycle(); bitmap.recycle(); handler.post { fail("OCR: ${e.message}") } }
    } catch(e:Exception) { handler.post { fail("Скриншот: ${e.message}") } }
    finally { buffer.close() }
   }
   override fun onFailure(errorCode:Int) { handler.post { fail("Снимок недоступен: $errorCode") } }
  })
 }
 private fun norm(s:String)=s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
 private fun namesMatch(actual:String,expected:String):Boolean {
  val a=norm(actual); val b=norm(expected)
  if(b.isEmpty() || a.isEmpty()) return false
  return a.contains(b) || (b.length>=5 && a.length>=4 && b.contains(a) && a.length>=b.length-2)
 }
 private fun findCandidates(lines:List<Pair<String,Rect>>):List<Candidate> {
  val result=mutableListOf<Candidate>()
  val levels=lines.filter { (t,r) ->
   t.trim().matches(Regex("[0-9]{1,2}")) && r.centerY()>height*.12 && r.centerY()<height*.86
  }
  for((levelText,lr) in levels) {
   val level=levelText.trim().toIntOrNull() ?: continue
   if(level!=targetLevel) continue
   // Game layouts vary with zoom: labels can be above, below or beside a badge.
   val names=lines.filter { (t,r) ->
    t.any { it.isLetter() } && r!=lr &&
    abs(r.centerX()-lr.centerX())<width*.22 &&
    abs(r.centerY()-lr.centerY())<height*.12 &&
    !t.contains("Крепость",true) && !t.contains("Точка сбора",true)
   }
   val named=names.minByOrNull { abs(it.second.centerX()-lr.centerX())+abs(it.second.centerY()-lr.centerY()) }
   val name=named?.first ?: ""
   val cx=lr.centerX().toFloat(); val cy=lr.centerY().toFloat()
   if(cx<width*.10f || cx>width*.90f || cy<height*.13f || cy>height*.86f) continue
   if(allowedKinds.isEmpty() || ignoreKinds.size>=6) continue
   val key="${row}:${direction}:${step}:${(cx/55).toInt()}:${(cy/55).toInt()}"
   if(key in rejected) continue
   // An unreadable name is a verification candidate, not a negative match.
   result.add(Candidate(cx,cy,name,level,-1))
  }
  return result.distinctBy { Pair((it.x/55).toInt(),(it.y/55).toInt()) }
   .sortedWith(compareBy<Candidate> { it.y }.thenBy { it.x })
 }
 private fun hasExpectedLevelNear(lines:List<Pair<String,Rect>>,x:Float,y:Float):Boolean {
  val levels=lines.filter { (t,r) ->
   t.trim().matches(Regex("[0-9]{1,2}")) &&
   abs(r.centerX()-x)<width*.30 && abs(r.centerY()-y)<height*.24
  }
  // If the badge is unreadable after zoom, require a name match on two frames;
  // never accept a clearly conflicting nearby badge as confirmation.
  return levels.isEmpty() || levels.any { it.first.trim().toIntOrNull()==targetLevel }
 }
 private fun locateCandidate(lines:List<Pair<String,Rect>>,candidate:Candidate?):Candidate? {
  if(candidate==null) return null
  val wanted=norm(candidate.name)
  val matches=lines.filter { (t,r) -> wanted.isNotEmpty() && (namesMatch(t,candidate.name)) && r.centerY()>height*.12 && r.centerY()<height*.85 }
  val nearest=matches.minByOrNull { hypot((it.second.centerX()-candidateX).toDouble(),(it.second.centerY()-candidateY).toDouble()) } ?: return null
  return candidate.copy(x=nearest.second.centerX().toFloat(),y=nearest.second.centerY().toFloat())
 }
 private fun hasNameNear(lines:List<Pair<String,Rect>>,x:Float,y:Float):Boolean {
  val wanted=norm(target)
  if(wanted.isEmpty()) return false
  return lines.any { (t,r) -> namesMatch(t,target) && abs(r.centerX()-x)<width*.30 && abs(r.centerY()-y)<height*.28 }
 }
 private fun centerTarget(x:Float,y:Float) {
  val dx=(width*.5f-x).coerceIn(-width*.22f,width*.22f)
  val dy=(height*.51f-y).coerceIn(-height*.18f,height*.18f)
  if(abs(dx)<width*.035f && abs(dy)<height*.035f) { centered=true; performPinch(width*.5f,height*.51f,true); return }
  status="Центрирование (${dx.toInt()}, ${dy.toInt()})"
  performAbsoluteSwipe(width*.5f,height*.51f,dx,dy,350)
 }
 private fun performAbsoluteSwipe(cx:Float,cy:Float,dx:Float,dy:Float,duration:Long) {
  val path=Path().apply {moveTo(cx,cy); lineTo(cx+dx,cy+dy)}
  val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,duration)).build()
  if(!dispatchGesture(gesture,object:GestureResultCallback(){
   override fun onCompleted(gestureDescription:GestureDescription?) { handler.postDelayed({scan()},moveDelayMs) }
   override fun onCancelled(gestureDescription:GestureDescription?) { fail("Центрирование прервано") }
  },handler)) fail("Жест центрирования отклонён")
 }
 private fun found() {
  running=false; phase=0; report("Цель найдена: ${if(searchMode==1) "уровень $targetLevel" else target}")
  notifyFound()
  Toast.makeText(this,status,Toast.LENGTH_LONG).show()
  // Do not tap automatically: the user can inspect the result and tap manually.
 }
 private fun performPinch(cx:Float,cy:Float,zoomIn:Boolean) {
  val from=width*(if(zoomIn) .105f else .155f)
  val to=width*(if(zoomIn) .155f else .105f)
  val x=cx.coerceIn(width*.25f,width*.75f)
  val y=cy.coerceIn(height*.25f,height*.70f)
  val p1=Path().apply { moveTo(x-from,y); lineTo(x-to,y) }
  val p2=Path().apply { moveTo(x+from,y); lineTo(x+to,y) }
  val gesture=GestureDescription.Builder()
   .addStroke(GestureDescription.StrokeDescription(p1,0,450))
   .addStroke(GestureDescription.StrokeDescription(p2,0,450)).build()
  if(!dispatchGesture(gesture,object:GestureResultCallback() {
   override fun onCompleted(gestureDescription:GestureDescription?) {
    if(zoomIn) zoomStep++ else zoomStep=(zoomStep-1).coerceAtLeast(0)
    handler.postDelayed({ scan() },moveDelayMs)
   }
   override fun onCancelled(gestureDescription:GestureDescription?) { fail("Жест масштаба прерван") }
  },handler)) fail("Жест масштаба не принят")
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
 private fun startNextRow(sample:IntArray,reason:String) {
  report(reason)
  edgeProbeStage=0; edgeEvidence=0; probeReverseChanged=false
  previousSample=sample; checkingMovement=true; verticalCheck=true
  uncertainVertical=0; verticalStillChecks=0; verticalTransitionPending=true
  report("Один вертикальный шаг на 22% экрана; после него смена направления")
  performSwipe(.50f,.61f,.50f,.39f)
 }
 private fun navigate(sample:IntArray) {
  if(!running) return
  if(checkingMovement && previousSample!=null) {
   val wasProbe=edgeProbeStage
   val wasVertical=verticalCheck
   val m=measureMotion(previousSample!!,sample,wasVertical)
   checkingMovement=false; previousSample=null; verticalCheck=false
   report("${if(wasVertical) "Вертикаль" else "Горизонталь"}: ${m.kind}; сдвиг (${m.dx},${m.dy}); разница ${"%.3f".format(Locale.US,m.baseline)}; совпадение ${"%.3f".format(Locale.US,m.score)}")
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
  report(if(diagnosticMode) "Диагностика: горизонтальный тест ${diagnosticChecks+1}/3" else "Сканирую: полоса ${row+1}, подтверждено $rowSteps, ${if(direction==1) "вправо" else "влево"}")
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
  val path=Path().apply { moveTo(width*x1,height*y1); lineTo(width*x2,height*y2) }
  val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,500)).build()
  report("Жест: отправка (${"%.2f".format(Locale.US,x1)},${"%.2f".format(Locale.US,y1)}) → (${"%.2f".format(Locale.US,x2)},${"%.2f".format(Locale.US,y2)})")
  val accepted=dispatchGesture(gesture,object:GestureResultCallback() {
   override fun onCompleted(gestureDescription:GestureDescription?) {
    if(running) { report("Жест: выполнен системой"); handler.postDelayed({ scan() },moveDelayMs) }
   }
   override fun onCancelled(gestureDescription:GestureDescription?) { if(running) fail("Жест прерван системой") }
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
