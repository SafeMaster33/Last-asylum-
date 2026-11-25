package com.safemaster.lastasylum

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import kotlin.math.roundToInt

internal fun Context.installedVersion(): String {
    val info = packageManager.getPackageInfo(packageName, 0)
    return "${info.versionName} (${info.longVersionCode})"
}

class MainActivity : Activity() {
    private val paper = Color.rgb(244, 247, 240)
    private val ink = Color.rgb(24, 48, 39)
    private val muted = Color.rgb(69, 94, 81)
    private val edge = Color.rgb(207, 218, 204)
    private val accent = Color.rgb(194, 232, 150)
    private val prefs by lazy { getSharedPreferences("scanner", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var primary: Button
    private lateinit var diagnostic: CheckBox

    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()

    private fun shape(fill: Int, radius: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (border) setStroke(dp(1), edge)
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun LinearLayout.put(view: View, gap: Int = 0) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }

    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(if (bold) ink else muted)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun LinearLayout.card() = column().also {
        it.background = shape(Color.WHITE, 18, true)
        it.setPadding(dp(16), dp(14), dp(16), dp(14))
        put(it, 12)
    }

    private fun button(title: String, fill: Int = paper, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 16f
        minHeight = dp(48)
        setTextColor(ink)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        stateListAnimator = null
        background = RippleDrawable(ColorStateList.valueOf(0x22000000), shape(fill, 12), null)
        setOnClickListener { action() }
    }

    private fun field(section: LinearLayout, title: String, value: String, numeric: Boolean): EditText {
        val caption = label(title, 14, true)
        val input = EditText(this).apply {
            id = View.generateViewId()
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setText(value)
            textSize = 17f
            minHeight = dp(48)
            setTextColor(ink)
            background = shape(paper, 10, true)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        caption.labelFor = input.id
        section.put(caption, 10)
        section.put(input, 3)
        return input
    }

    private fun picker(section: LinearLayout, title: String, items: Array<String>, selected: Int): Spinner {
        val caption = label(title, 14, true)
        val spinner = Spinner(this).apply {
            id = View.generateViewId()
            prompt = title
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            minimumHeight = dp(48)
            setSelection(selected.coerceIn(items.indices))
        }
        caption.labelFor = spinner.id
        section.put(caption, 6)
        section.put(spinner)
        return spinner
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val body = column().apply {
            setPadding(dp(16), dp(16), dp(16), dp(24))
            isFocusableInTouchMode = true
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(paper); addView(body) })
        body.put(label("Last Asylum Scanner", 26, true))
        body.put(label("Версия ${installedVersion()}", 13))

        val search = body.card()
        val mode = picker(search, "Искать по", arrayOf("Имени и уровню", "Уровню", "Имени"), prefs.getInt("mode", 0))
        val nameGroup = column()
        val levelGroup = column()
        search.put(nameGroup)
        search.put(levelGroup)
        val name = field(nameGroup, "Имя на карте", prefs.getString("name", "Doctor8rtx6hjd").orEmpty(), false)
        val level = field(levelGroup, "Уровень базы", prefs.getInt("level", 20).toString(), true)
        fun showFields(index: Int) {
            nameGroup.visibility = if (index == 1) View.GONE else View.VISIBLE
            levelGroup.visibility = if (index == 2) View.GONE else View.VISIBLE
        }
        showFields(mode.selectedItemPosition)
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showFields(position)
        }

        val advanced = column()
        val scale = picker(advanced, "Масштаб карты", arrayOf("Сохранять текущий масштаб", "Увеличивать для чтения имени"), prefs.getInt("scale", 1))
        val delay = field(advanced, "Ожидание после жеста, мс (500–15 000)", prefs.getLong("delay", 2500L).toString(), true)
        val retries = field(advanced, "Попытки сдвига (1–12)", prefs.getInt("retries", 5).toString(), true)
        advanced.put(label("Пропускать на карте", 16, true), 16)
        advanced.put(label("Неопределённые объекты остаются в поиске.", 14), 3)
        val categories = listOf("resources" to "Ресурсы", "mobs" to "Мобы", "enemies" to "Базы врагов",
            "neutral" to "Нейтральные базы", "allies" to "Базы союзников", "castles" to "Замки")
        val checks = categories.associate { (key, title) ->
            key to CheckBox(this).apply {
                text = title
                setTextColor(ink)
                minHeight = dp(48)
                isChecked = prefs.getBoolean("ignore_$key", false)
                advanced.put(this)
            }
        }
        diagnostic = CheckBox(this).apply {
            text = "Диагностика навигации"
            minHeight = dp(48)
            setTextColor(ink)
            isChecked = prefs.getBoolean("diagnostic", false)
        }
        advanced.put(diagnostic, 8)
        advanced.put(label("Диагностика выполняет три горизонтальных свайпа и один вертикальный. Кадры сохраняются в папке файлов приложения.", 14))

        fun applySettings(): Boolean {
            val selected = mode.selectedItemPosition
            val nick = name.text.toString().trim()
            val number = level.text.toString().toIntOrNull()
            if (!diagnostic.isChecked && selected != 1 && nick.isBlank()) {
                name.error = "Введите имя с карты"; name.requestFocus(); return false
            }
            if (!diagnostic.isChecked && selected != 2 && (number == null || number <= 0)) {
                level.error = "Введите уровень базы"; level.requestFocus(); return false
            }
            val targetLevel = number ?: 20
            val delayMs = (delay.text.toString().toLongOrNull() ?: 2500L).coerceIn(500L, 15000L)
            val retriesValue = (retries.text.toString().toIntOrNull() ?: 5).coerceIn(1, 12)
            val ignored = checks.filterValues { it.isChecked }.keys
            val update = prefs.edit().putInt("mode", selected).putString("name", nick).putInt("level", targetLevel)
                .putInt("scale", scale.selectedItemPosition).putLong("delay", delayMs).putInt("retries", retriesValue)
                .putBoolean("diagnostic", diagnostic.isChecked)
            checks.forEach { (key, box) -> update.putBoolean("ignore_$key", box.isChecked) }
            update.apply()
            ScannerService.instance?.configure(nick, targetLevel, selected, delayMs, retriesValue,
                scale = scale.selectedItemPosition, ignored = ignored, diagnostic = diagnostic.isChecked)
            return true
        }

        primary = button("", accent) {
            if (applySettings()) {
                val service = ScannerService.instance
                if (service == null) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    service.startScanning()
                    status.text = "Запущено. Откройте карту игры."
                }
            }
        }
        diagnostic.setOnCheckedChangeListener { _, _ -> refreshPrimary() }
        search.put(primary, 14)
        search.put(button("Сохранить для плавающей кнопки") {
            if (applySettings()) {
                status.text = "Настройки сохранены. На карте нажмите плавающую кнопку «Старт»."
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
        }, 6)

        val state = body.card()
        state.put(label("Состояние", 17, true))
        status = label("", 15).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        state.put(status, 6)
        state.put(button("Остановить") { ScannerService.instance?.stopScanning(); refreshStatus() }, 10)
        state.put(button("Обновить статус") { refreshStatus() }, 4)
        state.put(button("Журнал поиска") {
            val journal = ScannerService.instance?.diagnostics().orEmpty()
            AlertDialog.Builder(this).setTitle("Журнал поиска")
                .setMessage(journal.ifBlank { "Записей пока нет." }).setPositiveButton("Закрыть", null).show()
        }, 4)

        val settings = body.card()
        lateinit var toggle: Button
        advanced.visibility = View.GONE
        toggle = button("Дополнительные настройки ▾") {
            val expand = advanced.visibility == View.GONE
            advanced.visibility = if (expand) View.VISIBLE else View.GONE
            toggle.text = if (expand) "Свернуть настройки ▴" else "Дополнительные настройки ▾"
        }
        settings.put(toggle)
        settings.put(advanced)
        advanced.put(button("Настройки специальных возможностей") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, 12)
        body.put(label("Перед поиском выставьте масштаб и откройте верхний левый участок карты. Плавающую кнопку можно перетащить.", 14), 12)
    }

    private fun refreshPrimary() {
        primary.text = when {
            ScannerService.instance == null -> "Включить службу сканера"
            diagnostic.isChecked -> "Запустить диагностику"
            else -> "Начать поиск"
        }
    }

    private fun refreshStatus() {
        status.text = ScannerService.instance?.status ?: "Для поиска включите службу в специальных возможностях Android."
    }

    override fun onResume() {
        super.onResume()
        refreshPrimary()
        refreshStatus()
    }
}
