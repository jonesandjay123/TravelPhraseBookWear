/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter and
 * https://github.com/android/wear-os-samples/tree/main/ComposeAdvanced to find the most up to date
 * changes to the libraries and their usages.
 */

package com.jonesandjay123.travelphrasebook.presentation

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.firebase.crashlytics.buildtools.reloc.com.google.common.reflect.TypeToken
import com.google.gson.Gson
import com.jonesandjay123.travelphrasebook.presentation.theme.TravelPhraseBookWearTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    // 定義狀態變量
    private var isConnected by mutableStateOf(false)
    private var nodeInfo by mutableStateOf<String?>(null)

    // 新增一個狀態變量，用於存儲接收到的句子清單
    private var phraseList by mutableStateOf<List<Phrase>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("WearApp", "DataClient 監聽器已添加")
        // 註冊 DataClient 監聽器
        Wearable.getDataClient(this).dataItems.addOnCompleteListener { task ->
            if (task.isSuccessful && task.result != null) {
                Log.d("WearApp", "成功檢索到手錶端 DataItems")
                Log.d("WearApp", "task: ${task.result.count}")
                for (dataItem in task.result!!) {
                    Log.d("WearApp", "DataItem URI: ${dataItem.uri}")
                }
            } else {
                Log.d("WearApp", "檢索手錶端 DataItems 失敗")
            }
        }

        // 設置內容視圖
        setContent {
            TravelPhraseBookWearTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.background),
                    contentAlignment = Alignment.Center
                ) {
                    TimeText()
                    if (phraseList.isNotEmpty()) {
                        // 显示句子列表
                        PhraseListScreen(phraseList)
                    } else {
                        // 显示提示信息
                        Text(
                            text = "尚未接收到句子清单",
                            style = MaterialTheme.typography.body2,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }

        // 發送請求獲取連接資訊
        requestConnectionInfo()
    }

    override fun onResume() {
        super.onResume()
        Log.d("WearApp", "－－－－onResume 監聽器－－－")
        Wearable.getDataClient(this).addListener(this)
    }


    override fun onDestroy() {
        super.onDestroy()
        // 移除 DataClient 監聽器
        Log.d("WearApp", "－－－－DataClient 監聽器已移除－－－")
        Wearable.getDataClient(this).removeListener(this)
    }

    private fun requestConnectionInfo() {
        Log.d("WearApp", "－－－－requestConnectionInfo 觸發－－－")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 獲取連接的節點
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                Log.d("WearApp", "Connected nodes count: ${nodes.size}")

                if (nodes.isNotEmpty()) {
                    // 假設只處理第一個連接的節點
                    val node = nodes[0]
                    val nodeId = node.id
                    val nodeName = node.displayName
                    Log.d("WearApp", "Node ID: $nodeId, Node Name: $nodeName")

                    // 更新狀態變量
                    isConnected = true
                    nodeInfo = "Node ID: $nodeId\nNode Name: $nodeName"
                } else {
                    isConnected = false
                    nodeInfo = null
                }

            } catch (e: Exception) {
                Log.e("WearApp", "Error checking connection: ${e.message}")
                isConnected = false
                nodeInfo = null
            }
        }
    }

    @Composable
    fun ConnectionInfoText(isConnected: Boolean, nodeInfo: String?) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "旅行短語手冊v0.0.10.3",
                style = MaterialTheme.typography.title3,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (isConnected) "已聯通手機" else "未與手機連線",
                style = MaterialTheme.typography.body1,
                textAlign = TextAlign.Center
            )
            if (isConnected && nodeInfo != null) {
                Text(
                    text = nodeInfo,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    // 定義 Phrase 數據類
    data class Phrase(
        val en: String,
        val jp: String,
        val th: String,
        val zh: String,
        val order: Int
    )

    // 解析 JSON 字符串為 Phrase 列表
    private fun parseJsonToPhrases(jsonString: String): List<Phrase> {
        val gson = Gson()
        return gson.fromJson(jsonString, object : TypeToken<List<Phrase>>() {}.type)
    }

    // 實現 OnDataChangedListener 接口
    @SuppressLint("VisibleForTests")
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d("WearApp", "－－－－onDataChanged 被调用－－－－－")
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val uri = event.dataItem.uri
                if (uri.path?.startsWith("/phrases") == true) {
                    val dataMapItem = DataMapItem.fromDataItem(event.dataItem)
                    val phrasesJson = dataMapItem.dataMap.getString("phrases_json")
                    Log.d("WearApp", "收到測試數據: $phrasesJson")
                    if (phrasesJson != null) {
                        phraseList = parseJsonToPhrases(phrasesJson)
                        Log.d("WearApp", "收到新的句子清單，共 ${phraseList.size} 條")

                        // 添加 Toast 提示
                        runOnUiThread {
                            Toast.makeText(this, "已接收到 ${phraseList.size} 條句子", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // 顯示句子清單的組件
    @Composable
    fun PhraseListScreen(phrases: List<Phrase>) {
        // 使用 LazyColumn 来实现可滚动的列表
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(phrases) { phrase ->
                PhraseItem(phrase)
            }
        }
    }

    @Composable
    fun PhraseItem(phrase: Phrase) {
        // 使用 Card 或者 Surface 提升视觉层次
        androidx.wear.compose.material.Card(
            onClick = { /* 点击事件，未来可以添加 TTS 播放等功能 */ },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
        ) {
            Text(
                text = "${phrase.order}. ${phrase.zh}",
                style = MaterialTheme.typography.body1,
                color = Color.White,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
    }
}