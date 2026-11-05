package dev.apkpatcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainView(
    private val activity: Activity,
    private val listener: Listener,
) {
    interface Listener {
        fun onSourceSelected(source: ApkSource)
        fun onDownload(url: String)
        fun onChooseFile()
        fun onUpdatePatches()
        fun onPatch(userId: String, userToken: String, installAfter: Boolean)
        fun onInstall()
        fun onUninstall()
        fun onOpenCitizen()
        fun onCancelOperation()
    }

    private val controls = mutableListOf<View>()
    private lateinit var urlField: EditText
    private lateinit var userIdField: EditText
    private lateinit var tokenField: EditText
    private lateinit var selectedFileLabel: TextView
    private lateinit var installSwitch: Switch
    private lateinit var installButton: Button
    private lateinit var uninstallButton: Button
    private lateinit var launchButton: Button
    private lateinit var cancelOperationButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    val root: ScrollView = ScrollView(activity).apply {
        isFillViewport = true
        setBackgroundColor(Color.rgb(247, 248, 252))
    }

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(content)
        content.addView(TextView(activity).apply {
            setText(R.string.app_name)
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(28, 35, 58))
        })
        content.addView(TextView(activity).apply {
            setText(R.string.app_subtitle)
            textSize = 16f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(6), 0, dp(20))
        })

        content.addSection(R.string.section_get_apk)
        val sources = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        content.addView(sources, matchWrap())
        SourceCatalog.all.forEach { source ->
            sources.addAction(source.label) { listener.onSourceSelected(source) }
        }
        content.addView(TextView(activity).apply {
            setText(R.string.source_help)
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(12))
        })
        urlField = EditText(activity).apply {
            setHint(R.string.direct_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
        }
        content.addView(urlField, matchWrap())
        controls += urlField
        content.addAction(R.string.download_from_url) {
            listener.onDownload(urlField.text.toString().trim())
        }
        content.addAction(R.string.choose_local_file) { listener.onChooseFile() }
        content.addAction(R.string.update_from_morphe) { listener.onUpdatePatches() }

        selectedFileLabel = TextView(activity).apply {
            setText(R.string.no_apk_selected)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        }
        content.addView(selectedFileLabel)

        content.addSection(R.string.section_patch_options)
        installSwitch = Switch(activity).apply {
            setText(R.string.install_automatically)
            isChecked = true
            textSize = 16f
        }
        content.addView(installSwitch, matchWrap())
        controls += installSwitch
        content.addView(TextView(activity).apply {
            setText(R.string.credentials_help)
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(4))
        })
        userIdField = EditText(activity).apply {
            setHint(R.string.user_id_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        content.addView(userIdField, matchWrap())
        controls += userIdField
        tokenField = EditText(activity).apply {
            setHint(R.string.user_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        content.addView(tokenField, matchWrap())
        controls += tokenField

        content.addSection(R.string.section_build_install)
        content.addAction(R.string.patch_and_install) {
            listener.onPatch(
                userIdField.text.toString().trim(),
                tokenField.text.toString().trim(),
                installSwitch.isChecked,
            )
        }

        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        content.addView(progressBar, matchWrap().apply { topMargin = dp(16) })
        statusText = TextView(activity).apply {
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(statusText)

        cancelOperationButton = Button(activity).apply {
            setText(R.string.cancel_operation)
            setOnClickListener { listener.onCancelOperation() }
        }
        content.addView(cancelOperationButton, matchWrap())
        installButton = Button(activity).apply {
            setText(R.string.install_patched_apk)
            setOnClickListener { listener.onInstall() }
        }
        content.addView(installButton, matchWrap())
        uninstallButton = Button(activity).apply {
            setText(R.string.uninstall_conflict)
            setOnClickListener { listener.onUninstall() }
        }
        content.addView(uninstallButton, matchWrap())
        launchButton = Button(activity).apply {
            setText(R.string.open_citizen)
            setOnClickListener { listener.onOpenCitizen() }
        }
        content.addView(launchButton, matchWrap())
        content.addView(TextView(activity).apply {
            setText(R.string.authorization_notice)
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(24), 0, 0)
        })
    }

    fun render(state: WorkflowState) {
        controls.forEach { it.isEnabled = !state.running }
        selectedFileLabel.text = state.selectedFile?.let {
            "${state.selectedFileName}\n${formatBytes(state.selectedFileSize)}"
        } ?: activity.getString(R.string.no_apk_selected)
        statusText.text = state.status
        progressBar.visibility = if (state.running) View.VISIBLE else View.GONE
        if (state.progress == null) {
            progressBar.isIndeterminate = true
        } else {
            progressBar.isIndeterminate = false
            progressBar.max = 100
            progressBar.progress = state.progress
        }
        cancelOperationButton.visibility = visibleIf(
            state.phase in setOf(
                WorkflowPhase.DOWNLOADING,
                WorkflowPhase.IMPORTING,
                WorkflowPhase.UPDATING_PATCHES,
                WorkflowPhase.PATCHING,
            ),
        )
        installButton.visibility = visibleIf(state.patchResult != null && !state.installed && !state.running)
        uninstallButton.visibility = visibleIf(state.signatureConflict && !state.running)
        launchButton.visibility = visibleIf(state.installed && !state.running)
    }

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private fun LinearLayout.addSection(titleResource: Int) {
        addView(TextView(activity).apply {
            setText(titleResource)
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(49, 87, 213))
            setPadding(0, dp(20), 0, dp(8))
        })
    }

    private fun LinearLayout.addAction(labelResource: Int, action: () -> Unit) {
        addAction(activity.getString(labelResource), action)
    }

    private fun LinearLayout.addAction(label: String, action: () -> Unit) {
        val button = Button(activity).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        addView(button, if (orientation == LinearLayout.HORIZONTAL) {
            LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        } else {
            matchWrap().apply { topMargin = dp(6) }
        })
        controls += button
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
