package com.test.app.democode.binder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.test.app.democode.BackButton

class AidlActivity : AppCompatActivity() {
    private var remoteService: IDemoAidlInterface? = null
    private var isBound = false
    private var logText by mutableStateOf("service not connected")

    private val callback = object : IAidlCallback.Stub() {
        override fun onResult(message: String?) {
            appendLog("callback: $message")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteService = IDemoAidlInterface.Stub.asInterface(service)
            isBound = true
            remoteService?.registerCallback(callback)
            appendLog("service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            isBound = false
            appendLog("service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    AidlScreen(
                        logText = logText,
                        onBindService = ::bindRemoteService,
                        onCallAidl = ::callAidlMethods
                    )
                }
            }
        )
    }

    override fun onDestroy() {
        remoteService?.unregisterCallback(callback)
        if (isBound) {
            unbindService(connection)
        }
        super.onDestroy()
    }

    private fun bindRemoteService() {
        val intent = Intent(this, AidlRemoteService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun callAidlMethods() {
        val service = remoteService
        if (service == null) {
            appendLog("service not connected")
            return
        }

        val echoResult = service.echo("hello aidl")
        appendLog(echoResult)

        val inputBundle = Bundle().apply {
            putString(KEY_MESSAGE, "input from client")
        }
        service.setInput(inputBundle)
        appendLog("in bundle sent: ${inputBundle.getString(KEY_MESSAGE)}")

        val outputBundle = Bundle()
        service.fillOutput(outputBundle)
        appendLog("out bundle received: ${outputBundle.getString(KEY_MESSAGE)}")

        val inOutBundle = Bundle().apply {
            putString(KEY_MESSAGE, "inout from client")
        }
        service.updateInOut(inOutBundle)
        appendLog("inout bundle received: ${inOutBundle.getString(KEY_MESSAGE)}")

        service.sendOnewayMessage("oneway from client")
        appendLog("oneway method called")
    }

    private fun appendLog(message: String?) {
        runOnUiThread {
            logText = "$logText\n$message"
        }
    }

    private companion object {
        const val KEY_MESSAGE = "message"
    }
}

@Composable
private fun AidlScreen(
    logText: String,
    onBindService: () -> Unit,
    onCallAidl: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        BackButton()
        Spacer(modifier = Modifier.height(16.dp))
        BasicText(
            text = "AIDL Demo",
            style = TextStyle(
                color = Color.Black,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
        AidlButton(title = "绑定 remote 服务", onClick = onBindService)
        AidlButton(title = "调用 AIDL 方法", onClick = onCallAidl)
        Spacer(modifier = Modifier.height(16.dp))
        BasicText(
            text = logText,
            style = TextStyle(
                color = Color.Black,
                fontSize = 14.sp
            )
        )
    }
}

@Composable
private fun AidlButton(
    title: String,
    onClick: () -> Unit
) {
    BasicText(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        style = TextStyle(
            color = Color.Black,
            fontSize = 16.sp
        )
    )
}
