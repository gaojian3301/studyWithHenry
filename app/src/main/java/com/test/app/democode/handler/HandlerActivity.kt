package com.test.app.democode.handler

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.test.app.democode.BackButton
import com.test.app.democode.LogUtils
import java.util.concurrent.atomic.AtomicBoolean

class HandlerActivity : AppCompatActivity() {
    private val logs = mutableStateListOf<String>()
    private val stopping = AtomicBoolean(false)
    @Volatile
    private var workerHandler: Handler? = null
    @Volatile
    private var workerLooper: Looper? = null

    private val mainHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                MSG_FROM_BACKGROUND -> {
                    addLog("2. 子线程生产消息：${message.obj}")
                    addLog("3. sendMessage() 把消息放入 MainLooper 的 MessageQueue")
                    addLog("4. MainLooper 取出消息，UI Handler.handleMessage() 更新界面")
                }

                MSG_WORK_STARTED -> {
                    addLog("4. 主线程收到回执：Worker Handler 已开始处理")
                }

                MSG_WORK_FINISHED -> {
                    addLog("6. 主线程收到结果：${message.obj}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startWorkerLooper()
        setContentView(
            ComposeView(this).apply {
                setContent {
                    HandlerScreen(
                        logs = logs,
                        onMainLooperDemo = ::runMainLooperDemo,
                        onWorkerLooperDemo = ::runWorkerLooperDemo,
                        onClear = logs::clear
                    )
                }
            }
        )
    }

    private fun runMainLooperDemo() {
        logs.clear()
        addLog("1. 点击事件运行在 UI 线程：${threadName()}")
        Thread(
            {
                val producerThread = threadName()
                LogUtils.d(TAG, "2. 子线程生产消息：$producerThread")
                LogUtils.d(TAG, "3. sendMessage() 把消息放入 MainLooper 的 MessageQueue")
                mainHandler.sendMessage(
                    mainHandler.obtainMessage(MSG_FROM_BACKGROUND, producerThread)
                )
            },
            "message-producer"
        ).start()
    }

    private fun runWorkerLooperDemo() {
        logs.clear()
        addLog("1. UI 线程创建 Message：${threadName()}")
        val handler = workerHandler
        if (handler == null) {
            addLog("Worker Looper 尚未准备好，请稍后重试")
            return
        }
        handler.sendMessage(handler.obtainMessage(MSG_CALCULATE, 21, 0))
        addLog("2. sendMessage() 将任务放入 Worker MessageQueue")
    }

    private fun startWorkerLooper() {
        Thread(
            {
                // 普通线程没有消息循环，需要依次创建 Looper、Handler，再进入 loop()。
                Looper.prepare()
                val looper = Looper.myLooper() ?: error("Looper.prepare() failed")
                workerLooper = looper
                workerHandler = object : Handler(looper) {
                    override fun handleMessage(message: Message) {
                        if (message.what != MSG_CALCULATE) return

                        logFromAnyThread("3. Worker Looper 取出消息：${threadName()}")
                        mainHandler.sendEmptyMessage(MSG_WORK_STARTED)

                        SystemClock.sleep(500)
                        if (stopping.get()) return
                        val result = message.arg1 * 2

                        logFromAnyThread("5. Worker 处理完成，通过 Main Handler 回传结果")
                        mainHandler.sendMessage(
                            mainHandler.obtainMessage(MSG_WORK_FINISHED, "$result（${threadName()}）")
                        )
                    }
                }
                if (stopping.get()) {
                    looper.quit()
                    return@Thread
                }
                mainHandler.post {
                    if (!stopping.get()) {
                        addLog("Worker 消息循环已就绪：handler-worker")
                    }
                }
                Looper.loop()
            },
            "handler-worker"
        ).start()
    }

    private fun logFromAnyThread(text: String) {
        LogUtils.d(TAG, text)
        if (!stopping.get()) {
            mainHandler.post { addLog(text) }
        }
    }

    private fun addLog(text: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Compose 状态只能在主线程修改"
        }
        logs += text
        LogUtils.d(TAG, text)
    }

    private fun threadName(): String = Thread.currentThread().name

    override fun onDestroy() {
        stopping.set(true)
        workerHandler?.removeCallbacksAndMessages(null)
        workerLooper?.quitSafely()
        workerHandler = null
        workerLooper = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "HandlerActivity"
        const val MSG_FROM_BACKGROUND = 1
        const val MSG_CALCULATE = 2
        const val MSG_WORK_STARTED = 3
        const val MSG_WORK_FINISHED = 4
    }
}

@Composable
private fun HandlerScreen(
    logs: List<String>,
    onMainLooperDemo: () -> Unit,
    onWorkerLooperDemo: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        BackButton()
        Spacer(modifier = Modifier.height(8.dp))
        BasicText(
            text = "Handler / Looper / MessageQueue",
            style = TextStyle(
                color = Color.Black,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        ExplanationCard(
            title = "底层关系",
            body = """
                ThreadLocal 保存当前线程的 Looper
                Looper 持有一个 MessageQueue
                Handler 绑定 Looper，负责发送和处理 Message
                loop() 不断从队列取消息；队列为空时线程休眠，有消息时被唤醒
            """.trimIndent()
        )
        Spacer(modifier = Modifier.height(16.dp))
        DemoButton(
            text = "子线程 → Main Handler → 更新 UI",
            onClick = onMainLooperDemo
        )
        Spacer(modifier = Modifier.height(10.dp))
        DemoButton(
            text = "UI → Worker Looper → Main Handler",
            onClick = onWorkerLooperDemo
        )
        Spacer(modifier = Modifier.height(10.dp))
        DemoButton(text = "清空日志", onClick = onClear, color = Color(0xFF616161))
        Spacer(modifier = Modifier.height(20.dp))
        BasicText(
            text = "执行轨迹（同时可查看 Logcat）",
            style = TextStyle(
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF4F4F4))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (logs.isEmpty()) {
                LogText("点击按钮观察 Message 的流转过程")
            } else {
                logs.forEach { LogText(it) }
            }
        }
    }
}

@Composable
private fun ExplanationCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEFF5FF))
            .padding(12.dp)
    ) {
        BasicText(
            text = title,
            style = TextStyle(
                color = Color(0xFF174EA6),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        BasicText(
            text = body,
            style = TextStyle(color = Color(0xFF263238), fontSize = 14.sp)
        )
    }
}

@Composable
private fun DemoButton(
    text: String,
    onClick: () -> Unit,
    color: Color = Color(0xFF1565C0)
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
private fun LogText(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            color = Color(0xFF212121),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
    )
}
