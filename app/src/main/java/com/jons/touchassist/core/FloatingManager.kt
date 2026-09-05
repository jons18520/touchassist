package com.jons.touchassist.core

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
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
    private const val KEY_TARGETS = "click_targets"
    private const val KEY_GLOBAL_SETTINGS = "global_settings"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

    private const val MAX_TARGETS = 5

    // 目标视图尺寸（dp）- 必须与 layout_target_point.xml 中的尺寸一致
    private const val TARGET_VIEW_SIZE_DP = 36

    private var windowManager: WindowManager? = null
    private var controller: ClickServiceController? = null
    private var appContext: Context? = null
    private var controlPanelView: View? = null
    private var controlPanelParams: WindowManager.LayoutParams? = null

    private var playPauseButton: ImageButton? = null
    private var addButton: ImageButton? = null
    private var removeButton: ImageButton? = null
    private var editButton: ImageButton? = null
    private var settingsButton: ImageButton? = null
    private var profilesButton: ImageButton? = null
    private var exitButton: ImageButton? = null
    private var sharedPreferences: SharedPreferences? = null

    // 多点击目标管理
    private val clickTargets = mutableListOf<ClickTarget>()
    private var isEditMode = false

    // 配置方案列表（全局设置 + 触控目标数量/位置快照）
    private val profiles = mutableListOf<ConfigProfile>()

    data class ClickTarget(
        val id: String,
        var x: Float,
        var y: Float,
        // 触控类型按目标独立设置：编辑模式下点按目标点切换
        var clickType: AutoClickService.ClickType = AutoClickService.ClickType.SINGLE,
        var view: View? = null,
        var params: WindowManager.LayoutParams? = null
    )

    private var globalSettings = GlobalSettings()

    // 当前生效的方案 id；目标/设置被手动改动后置空，仅作为方案列表的提示标记
    private var activeProfileId: String? = null

    // 防止重复调用 restorePersistedSettings 时重复注入目标窗口
    private var hasRestoredSettings = false

    fun init(controller: ClickServiceController, context: Context) {
        this.controller = controller
        this.appContext = context
        this.windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun showControlPanel() {
        // appContext 为空说明 init 尚未执行（onServiceConnected 未回调），此时不能创建窗口
        if (controlPanelView != null || appContext == null) return

        val inflater = LayoutInflater.from(appContext)
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

    private fun createTargetView(target: ClickTarget) {
        if (target.view != null) return

        val inflater = LayoutInflater.from(appContext)
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

        // 更新按钮可见性 - 只在编辑模式显示
        updateTargetVisualState(target)

        // 编辑模式下点按（非拖动）目标点：切换单次/持续触控类型
        target.view?.setOnClickListener { toggleTargetType(target) }

        setupDraggableView(target.view!!, target.params!!, true, target)
    }

    private fun updateTargetVisualState(target: ClickTarget) {
        val context = appContext
        val iconView = target.view?.findViewById<ImageView>(R.id.iv_target_icon)
        iconView?.visibility = if (isEditMode) View.VISIBLE else View.INVISIBLE
        val badge = target.view?.findViewById<View>(R.id.v_target_type_badge)
        badge?.visibility = if (isEditMode) View.VISIBLE else View.GONE
        if (isEditMode && context != null) {
            val colorRes = if (target.clickType == AutoClickService.ClickType.SINGLE) {
                R.color.target_type_single
            } else {
                R.color.target_type_long_press
            }
            badge?.backgroundTintList = ColorStateList.valueOf(context.getColor(colorRes))
            badge?.contentDescription = context.getString(
                if (target.clickType == AutoClickService.ClickType.SINGLE) {
                    R.string.click_type_single
                } else {
                    R.string.click_type_long_press
                }
            )
        }
    }

    private fun toggleTargetType(target: ClickTarget) {
        val context = appContext ?: return
        if (!isEditMode || controller?.isClicking == true) return

        target.clickType = if (target.clickType == AutoClickService.ClickType.SINGLE) {
            AutoClickService.ClickType.LONG_PRESS
        } else {
            AutoClickService.ClickType.SINGLE
        }
        updateTargetVisualState(target)

        // 手动改动后不再对应任何已保存方案
        clearActiveProfileMark()
        persistAllTargets()
        syncTargetsToService()

        val typeName = context.getString(
            if (target.clickType == AutoClickService.ClickType.SINGLE) {
                R.string.click_type_single
            } else {
                R.string.click_type_long_press
            }
        )
        Toast.makeText(context, context.getString(R.string.target_type_switched, typeName), Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Target ${target.id} switched to ${target.clickType}")
    }

    private fun deleteTarget(target: ClickTarget) {
        clickTargets.remove(target)
        try {
            target.view?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        target.view = null
        target.params = null

        // 手动改动后不再对应任何已保存方案
        clearActiveProfileMark()

        // 更新服务中的目标列表
        syncTargetsToService()
        persistAllTargets()
        updatePanelButtonsEnabledState()
    }

    private fun setupControlPanelButtons() {
        controlPanelView?.let { view ->
            playPauseButton = view.findViewById(R.id.btn_play_pause)
            addButton = view.findViewById(R.id.btn_add)
            editButton = view.findViewById(R.id.btn_edit)
            settingsButton = view.findViewById(R.id.btn_settings)
            profilesButton = view.findViewById(R.id.btn_profiles)
            removeButton = view.findViewById(R.id.btn_remove)
            exitButton = view.findViewById(R.id.btn_exit)

            addButton?.setOnClickListener {
                addNewTarget()
            }

            removeButton?.setOnClickListener {
                deleteLastTarget()
            }

            editButton?.setOnClickListener {
                toggleEditMode()
            }

            settingsButton?.setOnClickListener {
                showGlobalSettingsDialog()
            }

            profilesButton?.setOnClickListener {
                showProfileListDialog()
            }

            playPauseButton?.setOnClickListener {
                Log.d("FloatingManager", "Play/Pause button clicked, isClicking=${controller?.isClicking}, isEditMode=$isEditMode")
                controller?.let { controller ->
                    if (controller.isClicking) {
                        Log.w("FloatingManager", "Pausing click task")
                        controller.pauseClickTask()
                    } else if (!isEditMode) {
                        Log.w("FloatingManager", "Starting click task")
                        controller.startClickTask()
                    } else {
                        Log.w("FloatingManager", "Cannot start: edit mode is on")
                    }
                }
            }

            exitButton?.setOnClickListener {
                showExitConfirmationDialog()
            }

            if (controlPanelView != null && controlPanelParams != null) {
                playPauseButton?.let {
                    setupControlPanelButtonDrag(
                        it,
                        controlPanelView!!,
                        controlPanelParams!!,
                        onTouchDown = {
                            if (controller?.isClicking == true) {
                                Log.w(TAG, "Pausing click task on touch down")
                                controller?.pauseClickTask()
                                true
                            } else {
                                false
                            }
                        }
                    )
                }
                addButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
                removeButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
                editButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
                settingsButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
                profilesButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
                exitButton?.let { setupControlPanelButtonDrag(it, controlPanelView!!, controlPanelParams!!) }
            }

            updatePanelButtonsEnabledState()

        }
    }

    private fun deleteLastTarget() {
        if (controller?.isClicking == true) return
        if (clickTargets.isEmpty()) return
        val target = clickTargets.last()
        deleteTarget(target)
    }

    private fun addNewTarget() {
        if (controller?.isClicking == true) return
        if (clickTargets.size >= MAX_TARGETS) {
            Toast.makeText(appContext, R.string.max_targets_reached, Toast.LENGTH_SHORT).show()
            return
        }

        // 在屏幕中央附近创建新目标
        val displayMetrics = appContext?.resources?.displayMetrics
        val screenWidth = displayMetrics?.widthPixels ?: 1080
        val screenHeight = displayMetrics?.heightPixels ?: 1920

        val newTarget = ClickTarget(
            id = UUID.randomUUID().toString(),
            x = (screenWidth / 2).toFloat(),
            y = (screenHeight / 2).toFloat()
        )

        clickTargets.add(newTarget)
        createTargetView(newTarget)

        // 手动改动后不再对应任何已保存方案
        clearActiveProfileMark()

        // 更新服务中的目标列表
        syncTargetsToService()
        persistAllTargets()

        Log.d(TAG, "Added new target at (${newTarget.x}, ${newTarget.y})")

        // 新增目标后自动开启编辑模式
        if (!isEditMode) {
            toggleEditMode()
        }
        updatePanelButtonsEnabledState()
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode

        // 更新所有目标的类型角标与图标可见性
        clickTargets.forEach { target ->
            updateTargetVisualState(target)
        }

        // 更新编辑按钮图标和状态
        editButton?.let { button ->
            if (isEditMode) {
                button.setImageResource(R.drawable.ic_check)
                button.contentDescription = appContext?.getString(R.string.edit_mode_off)
            } else {
                button.setImageResource(R.drawable.ic_edit)
                button.contentDescription = appContext?.getString(R.string.edit_mode_on)
            }
        }

        updatePanelButtonsEnabledState()

        // 同步目标点触摸性：编辑模式且未点击时可拖拽定位，其余情况不拦截触摸，
        // 确保暂停/非编辑状态下人工可以正常点击到下层应用。
        setTargetPointTouchable(isEditMode)
    }

    private fun showGlobalSettingsDialog() {
        appContext?.let { context ->
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_settings, null)

            val etInterval = dialogView.findViewById<EditText>(R.id.et_interval)
            val etSwipeDistance = dialogView.findViewById<EditText>(R.id.et_swipe_distance)
            val rgSwipeDirection = dialogView.findViewById<RadioGroup>(R.id.rg_swipe_direction)

            // 加载当前全局参数（触控类型不属于全局设置，按目标独立设置）
            etInterval.setText(globalSettings.interval.toString())
            etSwipeDistance.setText(globalSettings.swipeDistance.toString())

            // 加载当前方向
            when (globalSettings.swipeAngle) {
                270 -> rgSwipeDirection.check(R.id.rb_dir_up)
                90  -> rgSwipeDirection.check(R.id.rb_dir_down)
                180 -> rgSwipeDirection.check(R.id.rb_dir_left)
                0   -> rgSwipeDirection.check(R.id.rb_dir_right)
                else -> rgSwipeDirection.check(R.id.rb_dir_up)
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.target_settings_title)
                .setView(dialogView)
                .setPositiveButton(R.string.save) { _, _ ->
                    var interval = etInterval.text.toString().toLongOrNull() ?: 100L
                    interval = interval.coerceIn(1L, 1000L)

                    var swipeDistance = etSwipeDistance.text.toString().toIntOrNull() ?: 0
                    swipeDistance = swipeDistance.coerceAtLeast(0)

                    val swipeAngle = when (rgSwipeDirection.checkedRadioButtonId) {
                        R.id.rb_dir_up    -> 270
                        R.id.rb_dir_down  -> 90
                        R.id.rb_dir_left  -> 180
                        R.id.rb_dir_right -> 0
                        else              -> 270
                    }

                    globalSettings = GlobalSettings(
                        interval = interval,
                        swipeDistance = swipeDistance,
                        swipeAngle = swipeAngle
                    )

                    // 手动改动后不再对应任何已保存方案
                    clearActiveProfileMark()
                    // 先持久化再同步：进程在中途被杀时设置已落盘，下次启动 startClickTask 会重新同步
                    persistGlobalSettings()
                    syncTargetsToService()

                    Log.d(TAG, "Updated global settings: interval=$interval, distance=$swipeDistance, angle=$swipeAngle")
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        }
    }

    // ===================== 配置方案管理 =====================

    private fun showProfileListDialog() {
        appContext?.let { context ->
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_profiles, null)
            val listContainer = dialogView.findViewById<LinearLayout>(R.id.ll_profile_list)
            val emptyHint = dialogView.findViewById<TextView>(R.id.tv_profiles_empty)

            lateinit var dialog: AlertDialog

            fun refreshRows() {
                listContainer.removeAllViews()
                emptyHint.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                profiles.forEach { profile ->
                    val row = LayoutInflater.from(context).inflate(R.layout.item_profile, listContainer, false)
                    val nameView = row.findViewById<TextView>(R.id.tv_profile_name)
                    val marker = if (profile.id == activeProfileId) context.getString(R.string.profile_active_suffix) else ""
                    nameView.text = profile.name + marker

                    row.findViewById<View>(R.id.ll_profile_row)?.setOnClickListener {
                        applyProfile(profile)
                        dialog.dismiss()
                    }
                    row.findViewById<ImageButton>(R.id.btn_profile_delete)?.setOnClickListener {
                        showDeleteProfileDialog(profile) { refreshRows() }
                    }
                    listContainer.addView(row)
                }
            }

            dialog = AlertDialog.Builder(context)
                .setTitle(R.string.profile_list_title)
                .setView(dialogView)
                .setPositiveButton(R.string.profile_save_current) { _, _ ->
                    showSaveProfileDialog()
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

            refreshRows()

            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        }
    }

    private fun showSaveProfileDialog() {
        appContext?.let { context ->
            if (clickTargets.isEmpty()) {
                Toast.makeText(context, R.string.profile_no_targets, Toast.LENGTH_SHORT).show()
                return
            }

            val inputView = FrameLayout(context)
            val padding = (16 * context.resources.displayMetrics.density).toInt()
            inputView.setPadding(padding, 0, padding, 0)
            val input = EditText(context)
            input.hint = context.getString(R.string.profile_name_hint)
            input.setText(context.getString(R.string.profile_default_name, profiles.size + 1))
            inputView.addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.profile_new_title)
                .setView(inputView)
                .setPositiveButton(R.string.save) { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(context, R.string.profile_name_required, Toast.LENGTH_SHORT).show()
                    } else {
                        saveCurrentAsProfile(name)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        }
    }

    private fun saveCurrentAsProfile(name: String) {
        val context = appContext ?: return
        if (clickTargets.isEmpty()) {
            Toast.makeText(context, R.string.profile_no_targets, Toast.LENGTH_SHORT).show()
            return
        }

        val profile = ConfigProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            settings = globalSettings,
            targets = clickTargets.map { ProfileTarget(x = it.x, y = it.y, clickType = it.clickType) }
        )
        profiles.add(profile)
        activeProfileId = profile.id
        persistProfiles()
        persistActiveProfileId()

        Toast.makeText(context, context.getString(R.string.profile_saved, name), Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Saved profile ${profile.id}: name=$name, targets=${profile.targets.size}")
    }

    private fun showDeleteProfileDialog(profile: ConfigProfile, onDeleted: () -> Unit) {
        appContext?.let { context ->
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.profile_delete_title)
                .setMessage(context.getString(R.string.profile_delete_message, profile.name))
                .setPositiveButton(R.string.delete) { _, _ ->
                    deleteProfile(profile)
                    onDeleted()
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        }
    }

    private fun deleteProfile(profile: ConfigProfile) {
        val context = appContext ?: return
        profiles.removeAll { it.id == profile.id }
        if (activeProfileId == profile.id) {
            activeProfileId = null
            persistActiveProfileId()
        }
        persistProfiles()
        Toast.makeText(context, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun applyProfile(profile: ConfigProfile) {
        val context = appContext ?: return
        if (controller?.isClicking == true) return

        // 移除现有目标视图
        clickTargets.toList().forEach { target ->
            try {
                target.view?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {
            }
        }
        clickTargets.clear()

        // 按方案重建目标：位置夹取到屏幕范围内，数量不超过上限
        val sizePx = (TARGET_VIEW_SIZE_DP * context.resources.displayMetrics.density).toInt()
        profile.targets.take(MAX_TARGETS).forEach { position ->
            val (clampedX, clampedY) = clampOverlayPosition(position.x.toInt(), position.y.toInt(), sizePx, sizePx)
            val target = ClickTarget(
                id = UUID.randomUUID().toString(),
                x = clampedX.toFloat(),
                y = clampedY.toFloat(),
                clickType = position.clickType
            )
            clickTargets.add(target)
            createTargetView(target)
        }

        globalSettings = profile.settings
        activeProfileId = profile.id
        persistGlobalSettings()
        persistAllTargets()
        persistActiveProfileId()

        // 切换后通常需要微调位置，自动进入编辑模式
        if (!isEditMode) {
            toggleEditMode()
        }
        updatePanelButtonsEnabledState()
        syncTargetsToService()

        Toast.makeText(context, context.getString(R.string.profile_applied, profile.name), Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Applied profile ${profile.id}: name=${profile.name}, targets=${clickTargets.size}")
    }

    // 目标/设置被手动改动后，当前状态不再对应任何已保存方案
    private fun clearActiveProfileMark() {
        if (activeProfileId != null) {
            activeProfileId = null
            persistActiveProfileId()
        }
    }

    private fun persistProfiles() {
        sharedPreferences?.edit()
            ?.putString(KEY_PROFILES, ConfigProfileCodec.profilesToJson(profiles))
            ?.apply()
    }

    private fun persistActiveProfileId() {
        val editor = sharedPreferences?.edit() ?: return
        val id = activeProfileId
        if (id != null) {
            editor.putString(KEY_ACTIVE_PROFILE_ID, id)
        } else {
            editor.remove(KEY_ACTIVE_PROFILE_ID)
        }
        editor.apply()
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


    private fun updatePanelButtonsEnabledState() {
        val hasTargets = clickTargets.isNotEmpty()
        val isClicking = controller?.isClicking == true
        val canStart = (!isEditMode || isClicking) && hasTargets
        playPauseButton?.isEnabled = canStart
        playPauseButton?.alpha = if (canStart) 1f else 0.5f
        val canEdit = !isClicking
        editButton?.isEnabled = canEdit
        editButton?.alpha = if (canEdit) 1f else 0.5f
        settingsButton?.isEnabled = canEdit
        settingsButton?.alpha = if (canEdit) 1f else 0.5f
        profilesButton?.isEnabled = canEdit
        profilesButton?.alpha = if (canEdit) 1f else 0.5f
        exitButton?.isEnabled = canEdit
        exitButton?.alpha = if (canEdit) 1f else 0.5f
        val canAdd = canEdit && clickTargets.size < MAX_TARGETS
        addButton?.isEnabled = canAdd
        addButton?.alpha = if (canAdd) 1f else 0.5f
        val canRemove = canEdit && hasTargets
        removeButton?.isEnabled = canRemove
        removeButton?.alpha = if (canRemove) 1f else 0.5f
    }

    fun updateControlPanelState(isPlaying: Boolean) {
        val button = playPauseButton ?: return
        if (controlPanelView == null) return

        button.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        updatePanelButtonsEnabledState()
    }


    private fun showExitConfirmationDialog() {
        appContext?.let { context ->
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.exit_confirm_title)
                .setMessage(R.string.exit_confirm_message)
                .setPositiveButton(R.string.exit_confirm_action) { _, _ ->
                    controller?.stopClickService()
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        }
    }

    fun restorePersistedSettings() {
        val prefs = sharedPreferences ?: return
        if (hasRestoredSettings) return
        hasRestoredSettings = true

        var legacyFirstTargetSettings: GlobalSettings? = null

        try {
            val targetsJson = prefs.getString(KEY_TARGETS, null)
            if (targetsJson != null) {
                val array = JSONArray(targetsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (legacyFirstTargetSettings == null) {
                        // 旧版本把设置逐目标存在 click_targets 里，升级时迁移第一个目标的配置作为全局设置
                        legacyFirstTargetSettings = ConfigProfileCodec.settingsFromJson(obj.toString())
                    }
                    val target = ClickTarget(
                        id = obj.getString("id"),
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        clickType = ConfigProfileCodec.clickTypeFromRaw(obj.optString("clickType"))
                    )
                    clickTargets.add(target)
                    createTargetView(target)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore targets: ${e.message}")
        }

        globalSettings = prefs.getString(KEY_GLOBAL_SETTINGS, null)
            ?.let { ConfigProfileCodec.settingsFromJson(it) }
            ?: legacyFirstTargetSettings
            ?: GlobalSettings()
        persistGlobalSettings()
        syncTargetsToService()
        updatePanelButtonsEnabledState()

        restoreProfiles(prefs)
    }

    private fun restoreProfiles(prefs: SharedPreferences) {
        profiles.clear()
        val json = prefs.getString(KEY_PROFILES, null)
        if (json != null) {
            val loaded = ConfigProfileCodec.profilesFromJson(json)
            if (loaded != null) {
                profiles.addAll(loaded)
            } else {
                Log.w(TAG, "Failed to restore profiles, ignoring corrupted data")
            }
        }

        // 生效标记对应的方案可能已被删除，失效时清空
        val savedActiveId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        activeProfileId = savedActiveId?.takeIf { id -> profiles.any { it.id == id } }
    }

    private fun persistGlobalSettings() {
        sharedPreferences?.edit()
            ?.putString(KEY_GLOBAL_SETTINGS, ConfigProfileCodec.settingsToJson(globalSettings))
            ?.apply()
    }

    private fun persistAllTargets() {
        val array = JSONArray()
        clickTargets.forEach { t ->
            val obj = JSONObject()
            obj.put("id", t.id)
            obj.put("x", t.x.toDouble())
            obj.put("y", t.y.toDouble())
            obj.put("clickType", t.clickType.name)
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
                val fallbackSize = TARGET_VIEW_SIZE_DP * (appContext?.resources?.displayMetrics?.density ?: 1f)
                clickX = t.x + fallbackSize / 2f
                clickY = t.y + fallbackSize / 2f
            }

            AutoClickService.ClickTargetInfo(
                id = t.id,
                x = clickX,
                y = clickY,
                clickType = t.clickType,
                interval = globalSettings.interval,
                swipeDistance = globalSettings.swipeDistance,
                swipeAngle = globalSettings.swipeAngle
            )
        }

        controller?.updateClickTargets(targetInfos)

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
        val displayMetrics = appContext?.resources?.displayMetrics
        val screenWidth = displayMetrics?.widthPixels ?: 1080
        val screenHeight = displayMetrics?.heightPixels ?: 1920

        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val maxY = (screenHeight - viewHeight).coerceAtLeast(0)

        return desiredX.coerceIn(0, maxX) to desiredY.coerceIn(0, maxY)
    }

    private fun setupControlPanelButtonDrag(
        button: View,
        panelView: View,
        panelParams: WindowManager.LayoutParams,
        onTouchDown: (() -> Boolean)? = null
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var suppressClick = false
        val dragThreshold = 12f

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    suppressClick = onTouchDown?.invoke() ?: false
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
                        val suppress = suppressClick
                        suppressClick = false
                        suppress
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
                            // 手动改动后不再对应任何已保存方案
                            clearActiveProfileMark()
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
        removeButton = null
        editButton = null
        settingsButton = null
        profilesButton = null
        exitButton = null

        // 清理所有目标视图
        clickTargets.forEach { target ->
            target.view = null
            target.params = null
        }
        clickTargets.clear()

        // 允许下次显示悬浮窗时重新恢复
        hasRestoredSettings = false
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
        controller = null
        appContext = null
        sharedPreferences = null
    }
}
