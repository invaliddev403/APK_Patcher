package dev.apkpatcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_SOURCE = 10
        private const val REQUEST_FILE = 11
        private const val REQUEST_UNINSTALL = 12
    }

    private lateinit var mainView: MainView
    private lateinit var workflow: PatchWorkflowController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workflow = PatchWorkflowController(this, object : PatchWorkflowController.Listener {
            override fun onStateChanged(state: WorkflowState) {
                mainView.render(state)
                if (state.running) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            override fun onError(message: String) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        })
        mainView = MainView(this, object : MainView.Listener {
            override fun onSourceSelected(source: ApkSource) = openSource(source)
            override fun onDownload(url: String) = workflow.download(url)
            override fun onChooseFile() = chooseFile()
            override fun onUpdatePatches() = workflow.updatePatches()
            override fun onPatch(userId: String, userToken: String, installAfter: Boolean) =
                workflow.patch(userId, userToken, installAfter)
            override fun onInstall() = workflow.install()
            override fun onUninstall() = uninstallCitizen()
            override fun onOpenCitizen() = openCitizen()
            override fun onCancelOperation() = workflow.cancelOperation()
        })
        setContentView(mainView.root)
        workflow.initialize()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 13)
        }
    }

    private fun openSource(source: ApkSource) {
        if (workflow.state.running) return
        startActivityForResult(
            Intent(this, SourceActivity::class.java).putExtra(SourceActivity.EXTRA_URL, source.url),
            REQUEST_SOURCE,
        )
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.android.package-archive",
                "application/octet-stream",
                "application/zip",
            ))
        }
        try {
            startActivityForResult(intent, REQUEST_FILE)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.file_picker_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun openCitizen() {
        packageManager.getLaunchIntentForPackage(BuiltInPatchPacks.CITIZEN_PACKAGE_NAME)?.let(::startActivity)
    }

    private fun uninstallCitizen() {
        val packageUri = Uri.parse("package:${BuiltInPatchPacks.CITIZEN_PACKAGE_NAME}")
        try {
            startActivityForResult(Intent(Intent.ACTION_DELETE, packageUri), REQUEST_UNINSTALL)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_SOURCE -> data?.getStringExtra(SourceActivity.EXTRA_FILE)?.let {
                workflow.selectFile(File(it), downloaded = true)
            }
            REQUEST_FILE -> data?.data?.let(workflow::importFile)
            REQUEST_UNINSTALL -> workflow.install()
        }
    }

    override fun onDestroy() {
        workflow.close()
        super.onDestroy()
    }
}
