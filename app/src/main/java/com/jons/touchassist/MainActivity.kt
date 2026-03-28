package com.jons.touchassist

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jons.touchassist.core.AutoClickService

class MainActivity : AppCompatActivity() {

    private lateinit var overlayPermissionLayout: LinearLayout
    private lateinit var accessibilityPermissionLayout: LinearLayout
    private lateinit var overlayStatusIcon: ImageView
    private lateinit var accessibilityStatusIcon: ImageView
    private lateinit var startServiceButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        checkAllPermissions()
    }

    private fun initViews() {
        overlayPermissionLayout = findViewById(R.id.ll_overlay_permission)
        accessibilityPermissionLayout = findViewById(R.id.ll_accessibility_permission)
        overlayStatusIcon = findViewById(R.id.iv_overlay_status)
        accessibilityStatusIcon = findViewById(R.id.iv_accessibility_status)
        startServiceButton = findViewById(R.id.btn_start_service)
    }

    private fun setupClickListeners() {
        overlayPermissionLayout.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            }
        }

        accessibilityPermissionLayout.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                requestAccessibilityPermission()
            }
        }

        startServiceButton.setOnClickListener {
            startAutoClickService()
        }
    }

    private fun checkAllPermissions() {
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        val hasAccessibilityPermission = isAccessibilityServiceEnabled()

        overlayStatusIcon.setImageResource(
            if (hasOverlayPermission) R.drawable.ic_check else R.drawable.ic_close
        )

        accessibilityStatusIcon.setImageResource(
            if (hasAccessibilityPermission) R.drawable.ic_check else R.drawable.ic_close
        )

        startServiceButton.isEnabled = hasOverlayPermission && hasAccessibilityPermission
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expectedService = ComponentName(this, AutoClickService::class.java).flattenToString()
        return enabledServices
            .split(':')
            .any { it.equals(expectedService, ignoreCase = true) }
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, R.string.permission_overlay_message, Toast.LENGTH_LONG).show()

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestAccessibilityPermission() {
        Toast.makeText(this, R.string.permission_accessibility_message, Toast.LENGTH_LONG).show()

        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun startAutoClickService() {
        val intent = Intent(this, AutoClickService::class.java).apply {
            action = AutoClickService.ACTION_SHOW_OVERLAYS
        }
        startService(intent)

        Toast.makeText(this, R.string.service_running, Toast.LENGTH_SHORT).show()
        finish()
    }
}
