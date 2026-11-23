package com.safemaster.lastasylum

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val prefs=getSharedPreferences("scanner",MODE_PRIVATE)
  val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(30,35,30,20) }
  val title=TextView(this).apply { text="Last Asylum Scanner v0.8.3 (тест навигации)"; textSize=22f }; layout.addView(title)
  val modes=arrayOf("Имя и уровень (увеличение)","Только уровень (быстро)","Только имя")
  val mode=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,modes); setSelection(prefs.getInt("mode",0)) }; layout.addView(mode)
  val scale=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Масштаб: ручной", "Масштаб: контроль читаемости (экспериментально)")); setSelection(prefs.getInt("scale",0)) }; layout.addView(scale)
  val categories=listOf("resources" to "Ресурсы", "mobs" to "Мобы", "enemies" to "Базы врагов", "neutral" to "Нейтральные базы", "allies" to "Базы союзников", "castles" to "Замки")
  layout.addView(TextView(this).apply { text="Игнорировать категории (неопределённые объекты не исключаются)"; textSize=16f })
  val checks=categories.map { (key,label) -> key to CheckBox(this).apply { text=label; isChecked=prefs.getBoolean("ignore_$key",false); layout.addView(this) } }
  fun saveExtra() { val edit=prefs.edit().putInt("scale",scale.selectedItemPosition); checks.forEach { (key,box) -> edit.putBoolean("ignore_$key",box.isChecked) }; edit.apply() }
  fun ignored()=checks.filter { it.second.isChecked }.map { it.first }.toSet()
  val name=EditText(this).apply { setText(prefs.getString("name","Doctor8rtx6hjd")); hint="Никнейм" }; layout.addView(name)
  val level=EditText(this).apply { setText(prefs.getInt("level",20).toString()); hint="Уровень"; inputType=InputType.TYPE_CLASS_NUMBER }; layout.addView(level)
  val delay=EditText(this).apply { hint="Ожидание загрузки, мс (500–15000)"; inputType=InputType.TYPE_CLASS_NUMBER; setText(prefs.getLong("delay",2500).toString()) }; layout.addView(delay)
  val retries=EditText(this).apply { hint="Попыток сдвига (1–12)"; inputType=InputType.TYPE_CLASS_NUMBER; setText(prefs.getInt("retries",5).toString()) }; layout.addView(retries)
  val diagnostic=CheckBox(this).apply { text="Режим диагностики: 3 горизонтальных и 1 вертикальный свайп (без поиска цели)"; isChecked=prefs.getBoolean("diagnostic",false) }; layout.addView(diagnostic)
  val info=TextView(this).apply { text="Плавающая кнопка ▶ Старт / Ⅱ Стоп появляется после включения службы. Перед запуском выставьте масштаб карты в игре. Перетаскивайте кнопку пальцем." }; layout.addView(info)
  val status=TextView(this).apply { text="Начните с верхнего левого участка карты. При неподтверждённой границе сканер НЕ переходит вниз. Для диагностики включите режим 3+1; кадры сохраняются в Android/data/<пакет>/files."; textSize=15f }; layout.addView(status)
  fun button(label:String, action:()->Unit) { layout.addView(Button(this).apply { text=label; setOnClickListener { action() } }) }
  button("1. Включить службу") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
  button("2. Начать сканирование") {
   val selected=mode.selectedItemPosition
   val nick=name.text.toString().trim(); val lvl=level.text.toString().toIntOrNull()
   if ((selected==0 || selected==2) && nick.isBlank()) { status.text="Введите никнейм"; return@button }
   if ((selected==0 || selected==1) && (lvl==null || lvl<=0)) { status.text="Введите корректный уровень"; return@button }
   val delayValue=(delay.text.toString().toLongOrNull() ?: 2500L).coerceIn(500,15000)
   val retryValue=(retries.text.toString().toIntOrNull() ?: 5).coerceIn(1,12)
   saveExtra()
   prefs.edit().putBoolean("diagnostic",diagnostic.isChecked).apply()
   prefs.edit().putInt("mode",selected).putString("name",nick).putInt("level",lvl ?: 20).putLong("delay",delayValue).putInt("retries",retryValue).apply()
   val service=ScannerService.instance
   if(service==null) status.text="Включите службу специальных возможностей"
   else { service.configure(nick,lvl ?: 20,selected,delayValue,retryValue,scale=scale.selectedItemPosition,ignored=ignored(),diagnostic=diagnostic.isChecked); service.startScanning(); status.text="Запущено. Вернитесь на карту игры." }
  }
  button("Сохранить настройки для плавающей кнопки") {
   val selected=mode.selectedItemPosition
   val nick=name.text.toString().trim(); val lvl=level.text.toString().toIntOrNull() ?: 20
   val d=(delay.text.toString().toLongOrNull() ?: 2500L).coerceIn(500,15000)
   val r=(retries.text.toString().toIntOrNull() ?: 5).coerceIn(1,12)
   saveExtra()
   prefs.edit().putBoolean("diagnostic",diagnostic.isChecked).apply()
   prefs.edit().putInt("mode",selected).putString("name",nick).putInt("level",lvl).putLong("delay",d).putInt("retries",r).apply()
   ScannerService.instance?.configure(nick,lvl,selected,d,r,scale=scale.selectedItemPosition,ignored=ignored(),diagnostic=diagnostic.isChecked)
   status.text="Настройки сохранены. Перейдите в игру и нажмите ▶ на плавающей кнопке."
  }
  button("Остановить") { ScannerService.instance?.stopScanning(); status.text="Остановлено" }
  button("Показать журнал поиска") { status.text=ScannerService.instance?.diagnostics() ?: "Служба не запущена" }
  button("Показать статус") { status.text=ScannerService.instance?.status ?: "Служба не запущена" }
  setContentView(ScrollView(this).apply { addView(layout) })
 }
}
