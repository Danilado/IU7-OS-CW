package com.example.bt_sender

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat


class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("btmouse", MODE_PRIVATE) }

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null

    private var lastX = 0f
    private var lastY = 0f
    private var firstMove = true

    private var pendingDx = 0
    private var pendingDy = 0

    private val MOVE_INTERVAL_MS = 15L

    @Volatile private var moveSenderRunning = true

    private val btPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val ok = result.values.all { it }
            if (!ok) {
                Toast.makeText(this, "No BT permission", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = true

        // Apply safe area insets to the layout
        val root = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.setPadding(
                view.paddingLeft,
                systemBars.top + 16,   // TOP padding = safe area + dp
                view.paddingRight,
                systemBars.bottom + 16 // BOTTOM padding = safe area + dp
            )

            WindowInsetsCompat.CONSUMED
        }

        thread {
            while (moveSenderRunning) {
                Thread.sleep(MOVE_INTERVAL_MS)

                val dx: Int
                val dy: Int

                synchronized(this) {
                    dx = pendingDx
                    dy = pendingDy
                    pendingDx = 0
                    pendingDy = 0
                }

                if (dx != 0 || dy != 0) {
                    sendPacket(dx, dy, 0)
                }
            }
        }

        // views
        val touchpad = findViewById<View>(R.id.touchpad)
        val macInput = findViewById<EditText>(R.id.macInput)
        val btnLeft = findViewById<Button>(R.id.btnLeft)
        val btnRight = findViewById<Button>(R.id.btnRight)

        // restore saved MAC
        macInput.setText(prefs.getString("mac", ""))

        if (!hasPermissions()) {
            permissionLauncher.launch(btPermissions)
        }

        // connect when MAC changed (lossless and simple)
        macInput.setOnEditorActionListener { view, _, _ ->
            val mac = macInput.text.toString().trim()
            prefs.edit().putString("mac", mac).apply()
            connectToPc(mac)
            true
        }

        // mouse buttons
        btnLeft.setOnClickListener {
            sendPacket(0, 0, 1) // left click
        }
        btnRight.setOnClickListener {
            sendPacket(0, 0, 2) // right click
        }

        // touchpad movement
        touchpad.setOnTouchListener { _, ev ->
            handleTouch(ev)
            true
        }

        // auto-connect if MAC saved
        val savedMac = prefs.getString("mac", "")
        if (!savedMac.isNullOrEmpty()) connectToPc(savedMac)
    }

    private fun hasPermissions(): Boolean =
        btPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun connectToPc(mac: String) {
        if (mac.length < 17) {
            Toast.makeText(this, "Invalid MAC", Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return

        try {
            val device: BluetoothDevice = adapter.getRemoteDevice(mac)

            thread {
                try {
                    // RFCOMM channel 1 hack (driver listens there)
                    val method = device.javaClass.getMethod(
                        "createRfcommSocket",
                        Int::class.javaPrimitiveType
                    )
                    val sock = method.invoke(device, 1) as BluetoothSocket

                    adapter.cancelDiscovery()
                    sock.connect()

                    socket = sock
                    output = sock.outputStream

                    runOnUiThread {
                        Toast.makeText(this, "Connected to $mac", Toast.LENGTH_SHORT).show()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this, "Connect err: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "MAC error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
                firstMove = true
            }

            MotionEvent.ACTION_MOVE -> {
                val x = ev.x
                val y = ev.y

                if (firstMove) {
                    firstMove = false
                    lastX = x
                    lastY = y
                    return
                }

                val dx = (x - lastX).toInt()
                val dy = (y - lastY).toInt()

                lastX = x
                lastY = y

                synchronized(this) {
                    pendingDx += dx
                    pendingDy += dy
                }
            }
        }
    }

    private fun sendPacket(dx: Int, dy: Int, buttons: Int) {
        try {
            val out = output ?: return

            val buf = ByteArray(5)
            buf[0] = buttons.toByte()
            buf[1] = (dx shr 8).toByte()
            buf[2] = dx.toByte()
            buf[3] = (dy shr 8).toByte()
            buf[4] = dy.toByte()

            out.write(buf)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        moveSenderRunning = false

        try {
            output?.close()
            socket?.close()
        } catch (_: Exception) {}
    }
}
