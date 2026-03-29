package com.jons.touchassist.core

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.util.Log
import android.view.*
import android.widget.*
import com.jons.touchassist.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object FloatingManager {

    private const val TAG = "FloatingManager"
    private const val PREFS_NAME = "touch_assist_settings"
    private const val KEY_CLICK_INTERVAL = "click_interval"
    private const val KEY_TARGET_X = "target_x"
    private const val KEY_TARGET_Y = "target_y"
    private const val KEY_TARGETS = "click_targets"

    private const val MAX_TARGETS = 5

    // 目标视图尺寸（dp）- 必须与 layout_target_point.xml 中的尺寸一致
    private const val TARGET_VIEW_SIZE_DP = 36

    private var windowManager: WindowManager? = null
    private var service: AutoClickService? = null
    private var controlPanelView: View? = null
    private var controlPanelParams: WindowManager.LayoutParams? = null

    private var playPauseButton: ImageButton? = null
    private var addButton: ImageButton? = null
    private var editButton: ImageButton? = null
    private var sharedPreferences: SharedPreferences? = null

    // 多点击目标管理
    private val clickTargets = mutableListOf<ClickTarget>()
    private var isEditMode = false

    // 当前正在设置的目标 ID
    private var currentSettingTargetId: String? = null

    data class ClickTarget(
        val id: String,
        var x: Float,
        var y: Float,
        var clickType: ClickType = ClickType.SINGLE,
        var interval: Long = 100L,
        var swipeDistance: Int = 0,
        var swipeAngle: Int = 270,
        var view: View? = null,
        var params: WindowManager.LayoutParams? = null,
        var settingsButton: ImageButton? = null
    )

    enum class ClickType { SINGLE, LONG_PRESS }

    fun init(service: AutoClickService) {
        this.service = service
        this.windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.sharedPreferences = service.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun showControlPanel() {
        if (controlPanelView != null) return

        val inflater = LayoutInflater.from(service)
        controlPanelView = inflater.inflate(R.layout.layout_control_panel, null)

        controlPanelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        windowManager?.addView(controlPanelView, controlPanelParams)

        setupControlPanelButtons()
        setupDraggableView(controlPanelView!!, controlPanelParams!!)
    }

    fun showTargetPoint() {
        if (clickTargets.isEmpty()) {
            return
        }
    }

    private fun createTargetView(target: ClickTarget) {
        if (target.view != null) return

        val inflater = LayoutInflater.from(service)
        target.view = inflater.inflate(R.layout.layout_target_point, null)

        target.params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = target.x.toInt()
            y = target.y.toInt()
        }

        windowManager?.addView(target.view, target.params)

        // 获取设置按钮引用
        target.settingsButton = target.view?.findViewById(R.id.btn_target_settings)

        // 设置按钮事件
        target.settingsButton?.setOnClickListener {
            isEditMode = true
            updateTargetActionButtonsVisibility(target)
            showTargetSettingsDialog(target)
        }

        // 更新按钮可见性 - 只在编辑模式显示
        updateTargetActionButtonsVisibility(target)

        setupDraggableView(target.view!!, target.params!!, true, target)
    }

    private fun updateTargetActionButtonsVisibility(target: ClickTarget) {
        val actionContainer = target.view?.findViewById<FrameLayout>(R.id.fl_action_buttons)
        val iconView = target.view?.findViewById<ImageView>(R.id.iv_target_icon)
        if (isEditMode) {
            target.view?.visibility = View.VISIBLE
            actionContainer?.visibility = View.VISIBLE
            iconView?.visibility = View.VISIBLE
        } else {
            actionContainer?.visibility = View.GONE
            iconView?.visibility = View.INVISIBLE
        }
    }

    private fun deleteTarget(target: ClickTarget) {
        clickTargets.remove(target)
        try {
            target.view?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        target.view = null
        target.params = null
        target.settingsButton = null

        // 更新服务中的目标列表
        syncTargetsToService()
        persistAllTargets()
    }

    private fun setupControlPanelButtons() {
        controlPanelView?.let { view ->
            playPauseButton = view.findViewById(R.id.btn_play_pause)
            addButton = view.findViewById(R.id.btn_add)
            editButton = view.findViewById(R.id.btn_edit)
            val removeButton = view.findViewById<ImageButton>(R.id.btn_remove)
            val stopButton = view.findViewById<ImageButton>(R.id.btn_stop)
            val exitButton = view.findViewById<ImageButton>(R.id.btn_exit)

            addButton?.setOnClickListener {
                addNewTarget()
            }

            removeButton?.setOnClickListener {
                deleteLastTarget()
            }

            editButton?.setOnClickListener {
                toggleEditMode()
            }

            playPauseButton?.setOnClickListener {
                service?.let { service ->
                    if (service.isClicking) {
                        service.pauseClickTask()
                    } else if (!isEditMode) {
                        service.startClickTask()
                    }
                }
            }

            stopButton?.setOnClickListener {
                service?.stopClickTask()
            }

            exitButton?.setOnClickListener {
                showExitConfirmationDialog()
            }

            if (controlPanelView != null && controlPanelParams != null) {
                playPauseButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
                addButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
                removeButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
                editButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
                stopButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
                exitButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
            }

            updatePlayPauseButtonEnabledState()

        }
    }

    private fun deleteLastTarget() {
        if (clickTargets.isEmpty()) return
        val target = clickTargets.last()
        deleteTarget(target)
    }

    private fun addNewTarget() {
        if (clickTargets.size >= MAX_TARGETS) {
            Toast.makeText(service, R.string.max_targets_reached, Toast.LENGTH_SHORT).show()
            return
        }

        // 在屏幕中央附近创建新目标
        val displayMetrics = service?.resources?.displayMetrics
        val screenWidth = displayMetrics?.widthPixels ?: 1080
        val screenHeight = displayMetrics?.heightPixels ?: 1920

        val newTarget = ClickTarget(
            id = UUID.randomUUID().toString(),
            x = (screenWidth / 2).toFloat(),
            y = (screenHeight / 2).toFloat()
        )

        clickTargets.add(newTarget)
        createTargetView(newTarget)

        // 更新服务中的目标列表
        syncTargetsToService()
        persistAllTargets()

        Log.d(TAG, "Added new target at (${newTarget.x}, ${newTarget.y})")

        // 新增目标后自动开启编辑模式
        if (!isEditMode) {
            toggleEditMode()
        }
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode

        // 更新所有目标的按钮可见性
        clickTargets.forEach { target ->
            updateTargetActionButtonsVisibility(target)
        }

        // 更新编辑按钮图标和状态
        editButton?.let { button ->
            if (isEditMode) {
                button.setImageResource(R.drawable.ic_check)
                button.contentDescription = service?.getString(R.string.edit_mode_off)
            } else {
                button.setImageResource(R.drawable.ic_edit)
                button.contentDescription = service?.getString(R.string.edit_mode_on)
            }
        }

        updatePlayPauseButtonEnabledState()
    }

    private fun showTargetSettingsDialog(target: ClickTarget) {
        service?.let { context ->
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_settings, null)

            val rgClickType = dialogView.findViewById<RadioGroup>(R.id.rg_click_type)
            val tvIntervalLabel = dialogView.findViewById<TextView>(R.id.tv_interval_label)
            val etInterval = dialogView.findViewById<EditText>(R.id.et_interval)
            val tvSwipeDistanceLabel = dialogView.findViewById<TextView>(R.id.tv_swipe_distance_label)
            val etSwipeDistance = dialogView.findViewById<EditText>(R.id.et_swipe_distance)
            val tvSwipeDirectionLabel = dialogView.findViewById<TextView>(R.id.tv_swipe_direction_label)
            val rgSwipeDirection = dialogView.findViewById<RadioGroup>(R.id.rg_swipe_direction)

            // 加载当前目标设置
            when (target.clickType) {
                ClickType.SINGLE -> {
                    rgClickType.check(R.id.rb_click_type_single)
                    tvIntervalLabel.visibility = View.VISIBLE
                    etInterval.visibility = View.VISIBLE
                    tvSwipeDistanceLabel.visibility = View.GONE
                    etSwipeDistance.visibility = View.GONE
                    tvSwipeDirectionLabel.visibility = View.GONE
                    rgSwipeDirection.visibility = View.GONE
                }
                ClickType.LONG_PRESS -> {
                    rgClickType.check(R.id.rb_click_type_long_press)
                    tvIntervalLabel.visibility = View.GONE
                    etInterval.visibility = View.GONE
                    tvSwipeDistanceLabel.visibility = View.VISIBLE
                    etSwipeDistance.visibility = View.VISIBLE
                    tvSwipeDirectionLabel.visibility = View.VISIBLE
                    rgSwipeDirection.visibility = View.VISIBLE
                }
            }

            etInterval.setText(target.interval.toString())
            etSwipeDistance.setText(target.swipeDistance.toString())

            // 加载当前方向
            when (target.swipeAngle) {
                270 -> rgSwipeDirection.check(R.id.rb_dir_up)
                90  -> rgSwipeDirection.check(R.id.rb_dir_down)
                180 -> rgSwipeDirection.check(R.id.rb_dir_left)
                0   -> rgSwipeDirection.check(R.id.rb_dir_right)
                else -> rgSwipeDirection.check(R.id.rb_dir_up)
            }

            // 设置 RadioGroup 监听器
            rgClickType.setOnCheckedChangeListener { _, checkedId ->
                when (checkedId) {
                    R.id.rb_click_type_single -> {
                        tvIntervalLabel.visibility = View.VISIBLE
                        etInterval.visibility = View.VISIBLE
                        tvSwipeDistanceLabel.visibility = View.GONE
                        etSwipeDistance.visibility = View.GONE
                        tvSwipeDirectionLabel.visibility = View.GONE
                        rgSwipeDirection.visibility = View.GONE
                    }
                    R.id.rb_click_type_long_press -> {
                        tvIntervalLabel.visibility = View.GONE
                        etInterval.visibility = View.GONE
                        tvSwipeDistanceLabel.visibility = View.VISIBLE
                        etSwipeDistance.visibility = View.VISIBLE
                        tvSwipeDirectionLabel.visibility = View.VISIBLE
                        rgSwipeDirection.visibility = View.VISIBLE
                    }
                }
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.target_settings_title)
                .setView(dialogView)
                .setPositiveButton(R.string.save) { _, _ ->
                    val clickType = if (rgClickType.checkedRadioButtonId == R.id.rb_click_type_single) {
                        ClickType.SINGLE
                    } else {
                        ClickType.LONG_PRESS
                    }

                    var interval = etInterval.text.toString().toLongOrNull() ?: 100L
                    interval = interval.coerceIn(50L, 1000L)

                    var swipeDistance = etSwipeDistance.text.toString().toIntOrNull() ?: 0
                    swipeDistance = swipeDistance.coerceAtLeast(0)

                    val swipeAngle = when (rgSwipeDirection.checkedRadioButtonId) {
                        R.id.rb_dir_up    -> 270
                        R.id.rb_dir_down  -> 90
                        R.id.rb_dir_left  -> 180
                        R.id.rb_dir_right -> 0
                        else              -> 270
                    }

                    target.clickType = clickType
                    target.interval = interval
                    target.swipeDistance = swipeDistance
                    target.swipeAngle = swipeAngle

                    isEditMode = true
                    updateTargetActionButtonsVisibility(target)

                    // 更新服务中的目标
                    syncTargetsToService()
                    persistAllTargets()

                    Log.d(TAG, "Updated target ${target.id}: type=$clickType, interval=$interval, distance=$swipeDistance")
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        }
    }

    fun setTargetPointTouchable(isTouchable: Boolean) {
        clickTargets.forEach { target ->
            val view = target.view ?: return@forEach
            val params = target.params ?: return@forEach
            val manager = windowManager ?: return@forEach

            val shouldBeNotTouchable = !isTouchable
            val isCurrentlyNotTouchable =
                (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0

            if (shouldBeNotTouchable == isCurrentlyNotTouchable) {
                return@forEach
            }

            params.flags = if (isTouchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

            manager.updateViewLayout(view, params)
        }
    }


    private fun updatePlayPauseButtonEnabledState() {
        val hasTargets = clickTargets.isNotEmpty()
        val canStart = (!isEditMode || service?.isClicking == true) && hasTargets
        playPauseButton?.isEnabled = canStart
        playPauseButton?.alpha = if (canStart) 1f else 0.5f
    }

    fun updateControlPanelState(isPlaying: Boolean) {
        val button = playPauseButton ?: return
        if (controlPanelView == null) return

        button.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        updatePlayPauseButtonEnabledState()
    }


    private fun showExitConfirmationDialog() {
        service?.let { context ->
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.exit_confirm_title)
                .setMessage(R.string.exit_confirm_message)
                .setPositiveButton(R.string.exit_confirm_action) { _, _ ->
                    service?.stopClickService()
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        }
    }

    fun restorePersistedSettings() {
        val prefs = sharedPreferences ?: return
        val interval = prefs.getLong(KEY_CLICK_INTERVAL, 1000L)
        service?.updateSettings(interval)

        val targetsJson = prefs.getString(KEY_TARGETS, null) ?: return
        try {
            val array = JSONArray(targetsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val target = ClickTarget(
                    id = obj.getString("id"),
                    x = obj.getDouble("x").toFloat(),
                    y = obj.getDouble("y").toFloat(),
                    clickType = try { ClickType.valueOf(obj.getString("clickType")) } catch (_: Exception) { ClickType.SINGLE },
                    interval = obj.getLong("interval"),
                    swipeDistance = obj.getInt("swipeDistance"),
                    swipeAngle = obj.getInt("swipeAngle")
                )
                clickTargets.add(target)
                createTargetView(target)
            }
            syncTargetsToService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore targets: ${e.message}")
        }
    }

    private fun persistTargetPointPosition(target: ClickTarget) {
        sharedPreferences?.edit()?.apply {
            putInt(KEY_TARGET_X, target.x.toInt())
            putInt(KEY_TARGET_Y, target.y.toInt())
            apply()
        }
    }

    private fun persistAllTargets() {
        val array = JSONArray()
        clickTargets.forEach { t ->
            val obj = JSONObject()
            obj.put("id", t.id)
            obj.put("x", t.x.toDouble())
            obj.put("y", t.y.toDouble())
            obj.put("clickType", t.clickType.name)
            obj.put("interval", t.interval)
            obj.put("swipeDistance", t.swipeDistance)
            obj.put("swipeAngle", t.swipeAngle)
            array.put(obj)
        }
        sharedPreferences?.edit()?.putString(KEY_TARGETS, array.toString())?.apply()
    }

    fun syncTargetsToService() {
        val targetInfos = clickTargets.map { t ->
            val iconView = t.view?.findViewById<ImageView>(R.id.iv_target_icon)
            val location = IntArray(2)

            val clickX: Float
            val clickY: Float

            if (iconView != null && iconView.width > 0 && iconView.height > 0) {
                iconView.getLocationOnScreen(location)
                clickX = location[0] + iconView.width / 2f
                clickY = location[1] + iconView.height / 2f
            } else {
                val fallbackSize = TARGET_VIEW_SIZE_DP * (service?.resources?.displayMetrics?.density ?: 1f)
                clickX = t.x + fallbackSize / 2f
                clickY = t.y + fallbackSize / 2f
            }

            AutoClickService.ClickTargetInfo(
                id = t.id,
                x = clickX,
                y = clickY,
                clickType = when (t.clickType) {
                    ClickType.SINGLE -> AutoClickService.ClickType.SINGLE
                    ClickType.LONG_PRESS -> AutoClickService.ClickType.LONG_PRESS
                },
                interval = t.interval,
                swipeDistance = t.swipeDistance,
                swipeAngle = t.swipeAngle
            )
        }

        service?.updateClickTargets(targetInfos)

        targetInfos.forEach { info ->
            Log.d(TAG, "Sync target ${info.id}: (${info.x}, ${info.y}) type=${info.clickType} interval=${info.interval}")
        }
    }

    fun getClickTargets(): List<ClickTarget> = clickTargets.toList()

    fun isEditMode(): Boolean = isEditMode

    private fun clampOverlayPosition(
        desiredX: Int,
        desiredY: Int,
        viewWidth: Int,
        viewHeight: Int
    ): Pair<Int, Int> {
        val displayMetrics = service?.resources?.displayMetrics
        val screenWidth = displayMetrics?.widthPixels ?: 1080
        val screenHeight = displayMetrics?.heightPixels ?: 1920

        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val maxY = (screenHeight - viewHeight).coerceAtLeast(0)

        return desiredX.coerceIn(0, maxX) to desiredY.coerceIn(0, maxY)
    }

    private fun setupControlPanelButtonDrag(
        button: View,
        panelView: View,
        panelParams: WindowManager.LayoutParams
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = 12f

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = panelParams.x
                    initialY = panelParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = kotlin.math.abs(event.rawX - initialTouchX)
                    val deltaY = kotlin.math.abs(event.rawY - initialTouchY)

                    if (deltaX > dragThreshold || deltaY > dragThreshold) {
                        isDragging = true
                        v.isPressed = false
                        val desiredX = initialX + (event.rawX - initialTouchX).toInt()
                        val desiredY = initialY + (event.rawY - initialTouchY).toInt()

                        val panelWidth = if (panelView.width > 0) panelView.width else panelView.measuredWidth
                        val panelHeight = if (panelView.height > 0) panelView.height else panelView.measuredHeight
                        val (newX, newY) = clampOverlayPosition(desiredX, desiredY, panelWidth, panelHeight)

                        panelParams.x = newX
                        panelParams.y = newY
                        windowManager?.updateViewLayout(panelView, panelParams)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        v.isPressed = false
                        v.cancelLongPress()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    // 跟踪点击状态，点击期间禁用拖拽
    private var isServiceClicking = false

    private fun setupDraggableView(
        view: View,
        params: WindowManager.LayoutParams,
        isTargetPoint: Boolean = false,
        target: ClickTarget? = null
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = 20f

        view.setOnTouchListener { _, event ->
            // 目标点只有在编辑模式下才可拖拽
            if (isTargetPoint && !isEditMode) {
                return@setOnTouchListener false
            }

            // 点击期间禁用目标拖拽
            if (isTargetPoint && isServiceClicking) {
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    if (isTargetPoint) {
                        view.animate()
                            .scaleX(0.9f)
                            .scaleY(0.9f)
                            .alpha(0.85f)
                            .setDuration(90L)
                            .start()
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = kotlin.math.abs(event.rawX - initialTouchX)
                    val deltaY = kotlin.math.abs(event.rawY - initialTouchY)

                    if (deltaX > dragThreshold || deltaY > dragThreshold) {
                        isDragging = true
                        val desiredX = initialX + (event.rawX - initialTouchX).toInt()
                        val desiredY = initialY + (event.rawY - initialTouchY).toInt()

                        val viewWidth = if (view.width > 0) view.width else view.measuredWidth
                        val viewHeight = if (view.height > 0) view.height else view.measuredHeight
                        val (newX, newY) = clampOverlayPosition(desiredX, desiredY, viewWidth, viewHeight)

                        params.x = newX
                        params.y = newY
                        windowManager?.updateViewLayout(view, params)

                        target?.let {
                            it.x = newX.toFloat()
                            it.y = newY.toFloat()
                            persistAllTargets()
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isTargetPoint) {
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(120L)
                            .start()
                    }
                    if (isDragging) {
                        isDragging = false
                        // 拖拽结束时同步坐标到服务
                        target?.let {
                            it.x = params.x.toFloat()
                            it.y = params.y.toFloat()
                            persistAllTargets()
                            syncTargetsToService()
                            Log.d(TAG, "Drag ended for target ${it.id}: (${it.x}, ${it.y})")
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    fun setClickingState(clicking: Boolean) {
        isServiceClicking = clicking
    }

    private fun clearViewReferences() {
        controlPanelView = null
        controlPanelParams = null
        playPauseButton = null
        addButton = null
        editButton = null

        // 清理所有目标视图
        clickTargets.forEach { target ->
            target.view = null
            target.params = null
            target.settingsButton = null
        }
        clickTargets.clear()
    }

    fun hideAllViews() {
        try {
            controlPanelView?.let { windowManager?.removeView(it) }
            clickTargets.forEach { target ->
                target.view?.let { windowManager?.removeView(it) }
            }
        } catch (e: IllegalArgumentException) {
            // 视图已移除，忽略此异常
        } catch (e: WindowManager.BadTokenException) {
            // 窗口令牌无效，忽略此异常
        } catch (e: Exception) {
            android.util.Log.e("FloatingManager", "Error hiding views: ${e.message}")
        } finally {
            clearViewReferences()
        }
    }

    fun removeAllViews() {
        hideAllViews()
        windowManager = null
        service = null
        sharedPreferences = null
    }
}
