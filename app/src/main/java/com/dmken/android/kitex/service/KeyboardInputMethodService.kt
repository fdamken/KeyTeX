package com.dmken.android.kitex.service

import android.Manifest
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.support.v13.view.inputmethod.EditorInfoCompat
import android.support.v13.view.inputmethod.InputConnectionCompat
import android.support.v13.view.inputmethod.InputContentInfoCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.util.Log
import android.view.View
import android.widget.Toast
import com.dmken.android.kitex.R
import com.dmken.android.kitex.activity.MainActivity
import com.dmken.android.kitex.preference.Preferences
import com.dmken.android.kitex.util.CommonConstants
import com.dmken.android.kitex.util.PermissionUtil
import java.io.InputStream
import java.util.*

class KeyboardInputMethodService : InputMethodService(), KeyboardView.OnKeyboardActionListener {
    companion object {
        val MAX_HEIGHT = 512
        val MARGIN_RIGHTLEFT = 50
        val MARGIN_TOPBOTTOM = 100
    }

    private var caps = false

    private lateinit var keyboard: Keyboard;
    private lateinit var keyboardView: KeyboardView

    override fun onCreateInputView(): View {
        // Thread: UI

        if (!PermissionUtil.arePermissionsGranted(this)) {
            throw IllegalStateException("Permissions not granted!")
        }

        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null) as KeyboardView
        keyboard = Keyboard(this, R.xml.keys_layout)

        keyboardView.keyboard = keyboard
        keyboardView.setOnKeyboardActionListener(this)

        return keyboardView
    }

    override fun swipeRight() {}

    override fun onPress(code: Int) {}

    override fun onRelease(code: Int) {}

    override fun swipeLeft() {}

    override fun swipeUp() {}

    override fun swipeDown() {}

    override fun onKey(code: Int, secondaryCodes: IntArray?) {
        // Thread: UI

        val setCaps = { shouldCaps: Boolean ->
            if (caps != shouldCaps) {
                caps = shouldCaps
                keyboard.setShifted(caps)
                keyboardView.invalidateAllKeys()
            }
        }

        when {
            code == Keyboard.KEYCODE_DELETE -> currentInputConnection.deleteSurroundingText(1, 0)
            code == Keyboard.KEYCODE_DONE -> {
                // TODO: Is there a better way to get ALL TEXT of the input connection?
                currentInputConnection.performContextMenuAction(android.R.id.selectAll)
                val text = (currentInputConnection.getSelectedText(0) ?: "").toString()

                setCaps(false)

                startCompile(text)
            }
            code == Keyboard.KEYCODE_SHIFT -> setCaps(!caps)
            code < 0 -> throw IllegalStateException("Unknown code $code.")
            else -> {
                var capsChanged: Boolean = false;
                val char = code.toChar()
                val text: String;
                if (caps) {
                    when {
                        char == 'ß' -> {
                            text = "SS"

                            Log.d("DEMO", "<${code.toChar()}> <${secondaryCodes!!.map { c -> c.toChar() }}>")

                            capsChanged = true
                        }
                        char.isLetter() -> {
                            text = char.toUpperCase().toString()

                            capsChanged = true
                        }
                        else -> text = char.toString()
                    }
                } else {
                    text = char.toString()
                }
                currentInputConnection.commitText(text, text.length)

                if (capsChanged) {
                    setCaps(false)
                }
            }
        }
    }

    override fun onText(text: CharSequence?) {}

    private fun startCompile(code: String) {
        // Thread: UI

        Toast.makeText(this, getString(R.string.msg_compileStarted), Toast.LENGTH_LONG).show()

        LatexService().retrieveEquation(code, Preferences.getLatexEnvironment(applicationContext), { state, bytes ->
            // Thread: Web

            Handler(Looper.getMainLooper()).post {
                // Thread: UI

                when (state) {
                    LatexService.LatexState.INVALID_EQUATION -> Toast.makeText(applicationContext, getString(R.string.msg_invalidEquation), Toast.LENGTH_LONG).show()
                    LatexService.LatexState.SERVER_ERROR -> Toast.makeText(applicationContext, getString(R.string.msg_serverError), Toast.LENGTH_LONG).show()
                    LatexService.LatexState.SUCCESSFUL -> finishedCompilation(code, bytes!!) // '!!' is safe here as SUCCESSFUL implies that bytes != null
                }
            }
        })
    }

    private fun finishedCompilation(code: String, bytes: InputStream) {
        // Thread: UI

        val name = "equation-${Date()}"

        val values = ContentValues()
        values.put(MediaStore.Images.Media.TITLE, name)
        values.put(MediaStore.Images.Media.MIME_TYPE, CommonConstants.IMAGE_MIME_TYPE)
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        AsyncTask.execute {
            // Thread: Web

            contentResolver.openOutputStream(uri).use { out -> bytes.copyTo(out) }

            Handler(Looper.getMainLooper()).post {
                // Thread: UI

                Toast.makeText(this, "The compilation was finished!", Toast.LENGTH_LONG).show()

                when (Preferences.getCodeHandling(applicationContext)) {
                    Preferences.CodeHandling.DISCARD -> currentInputConnection.commitText("", 1)
                    Preferences.CodeHandling.COPY_TO_CLIPBOARD -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip = ClipData.newPlainText("latex-code", code)
                    }
                    Preferences.CodeHandling.DO_NOTHING -> { }
                }

                if (isCommitContentSupported()) {
                    val flag: Int;
                    if (Build.VERSION.SDK_INT >= 25) {
                        flag = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
                    } else {
                        flag = 0
                        grantUriPermission(currentInputEditorInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val info = InputContentInfoCompat(uri, ClipDescription(name, arrayOf(CommonConstants.IMAGE_MIME_TYPE)), null)
                    InputConnectionCompat.commitContent(currentInputConnection, currentInputEditorInfo, info, flag, null)
                } else {
                    Toast.makeText(applicationContext, getText(R.string.msg_commitContentNotSupported), Toast.LENGTH_SHORT).show()

                    val share = Intent(Intent.ACTION_SEND)
                    share.putExtra(Intent.EXTRA_STREAM, uri)
                    startActivity(Intent.createChooser(share, getText(R.string.label_share)))
                }
            }
        }
    }

    private fun isCommitContentSupported(): Boolean {
        if (currentInputConnection == null) {
            return false
        }
        if (currentInputEditorInfo == null) {
            return false
        }
        return EditorInfoCompat.getContentMimeTypes(currentInputEditorInfo).contains(CommonConstants.IMAGE_MIME_TYPE)
    }

    // https://stackoverflow.com/a/28367226/4907452
    private fun resize(image: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (maxHeight > 0 && maxWidth > 0) {
            val width = image.width
            val height = image.height
            val ratioBitmap = width.toFloat() / height.toFloat()
            val ratioMax = maxWidth.toFloat() / maxHeight.toFloat()

            var finalWidth = maxWidth
            var finalHeight = maxHeight
            if (ratioMax > ratioBitmap) {
                finalWidth = (maxHeight.toFloat() * ratioBitmap).toInt()
            } else {
                finalHeight = (maxWidth.toFloat() / ratioBitmap).toInt()
            }
            return Bitmap.createScaledBitmap(image, finalWidth, finalHeight, true)
        } else {
            return image
        }
    }

}
