package com.jonesandjay123.travelphrasebook.presentation

import android.annotation.SuppressLint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberPickerState
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.jonesandjay123.travelphrasebook.presentation.theme.TravelPhraseBookWearTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private var isConnected by mutableStateOf(false)
    private var nodeInfo by mutableStateOf<String?>(null)

    // 儲存接收到的句子清單
    private var phraseList by mutableStateOf<List<Phrase>>(emptyList())

    private lateinit var tts: TextToSpeech

    private var currentLanguage by mutableStateOf("zh")
    private val languageOptions = listOf("zh", "en", "jp", "th")
    private val languageDisplayNames = listOf("中", "英", "日", "泰")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 TTS
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.ERROR) {
                val result = tts.setLanguage(Locale.TRADITIONAL_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("WearApp", "TTS 不支援所選語言")
                    Toast.makeText(this, "TTS 不支援所選語言", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e("WearApp", "TTS 初始化失敗")
            }
        }

        // 從 SharedPreferences 讀取 currentLanguage
        val sharedPreferences = getSharedPreferences("app_preferences", MODE_PRIVATE)
        currentLanguage = sharedPreferences.getString("current_language", "zh") ?: "zh"

        // 從 SharedPreferences 讀取 phrases_json
        val phrasesJson = sharedPreferences.getString("phrases_json", null)
        if (phrasesJson != null) {
            phraseList = parseJsonToPhrases(phrasesJson)
        }

        // 註冊 DataClient 監聽器
        Wearable.getDataClient(this).addListener(this)

        // 設定內容視圖
        // 設定內容視圖
        setContent {
            TravelPhraseBookWearTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.background)
                ) {
                    if (phraseList.isNotEmpty()) {
                        // 句子列表
                        PhraseListScreen(
                            phrases = phraseList,
                            onPhraseClick = { phrase ->
                                val textToSpeak = when (currentLanguage) {
                                    "zh" -> phrase.zh
                                    "en" -> phrase.en
                                    "jp" -> phrase.jp
                                    "th" -> phrase.th
                                    else -> phrase.zh
                                }
                                speakText(textToSpeak, currentLanguage)
                            },
                            currentLanguage = currentLanguage,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        // 顯示提示訊息
                        Text(
                            text = "尚未接收到句子清單",
                            style = MaterialTheme.typography.body2,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                            modifier = Modifier
                                .padding(16.dp)
                                .weight(1f)
                        )
                    }

                    // 將 LanguagePicker 放在底部
                    LanguagePicker(
                        options = languageDisplayNames,
                        selectedIndex = languageOptions.indexOf(currentLanguage),
                        onLanguageSelected = { index ->
                            currentLanguage = languageOptions[index]
                            // 保存 currentLanguage 到 SharedPreferences
                            val sharedPreferences = getSharedPreferences("app_preferences", MODE_PRIVATE)
                            sharedPreferences.edit().putString("current_language", currentLanguage).apply()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp) // 您可以根據需要調整高度
                    )
                }
            }
        }

        // 發送請求獲取連接資訊
        requestConnectionInfo()
    }


    override fun onDestroy() {
        super.onDestroy()
        // 關閉 TTS
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        // 移除 DataClient 監聽器
        Log.d("WearApp", "－－－－DataClient 監聽器已移除－－－")
        Wearable.getDataClient(this).removeListener(this)
    }

    private fun requestConnectionInfo() {
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

                    // 更新狀態變數
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

    // 定義 Phrase 資料類
    data class Phrase(
        val en: String,
        val jp: String,
        val th: String,
        val zh: String,
        val order: Int
    )

    // 解析 JSON 字串
    private fun parseJsonToPhrases(jsonString: String): List<Phrase> {
        val gson = Gson()
        return gson.fromJson(jsonString, object : com.google.gson.reflect.TypeToken<List<Phrase>>() {}.type)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val uri = event.dataItem.uri
                if (uri.path?.startsWith("/phrases") == true) {
                    val dataMapItem = DataMapItem.fromDataItem(event.dataItem)
                    val phrasesJson = dataMapItem.dataMap.getString("phrases_json")
                    Log.d("WearApp", "收到測試資料: $phrasesJson")
                    if (phrasesJson != null) {
                        phraseList = parseJsonToPhrases(phrasesJson)

                        // 保存 phrasesJson 到 SharedPreferences
                        val sharedPreferences = getSharedPreferences("app_preferences", MODE_PRIVATE)
                        sharedPreferences.edit().putString("phrases_json", phrasesJson).apply()

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
    fun PhraseListScreen(
        phrases: List<Phrase>,
        onPhraseClick: (Phrase) -> Unit,
        currentLanguage: String,
        modifier: Modifier = Modifier
    ) {
        LazyColumn(
            modifier = modifier.fillMaxSize()
        ) {
            items(phrases) { phrase ->
                PhraseItem(phrase, onPhraseClick, currentLanguage)
            }
        }
    }

    @Composable
    fun PhraseItem(
        phrase: Phrase,
        onPhraseClick: (Phrase) -> Unit,
        currentLanguage: String
    ) {
        androidx.wear.compose.material.Card(
            onClick = { onPhraseClick(phrase) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
        ) {
            val displayText = when (currentLanguage) {
                "zh" -> phrase.zh
                "en" -> phrase.en
                "jp" -> phrase.jp
                "th" -> phrase.th
                else -> phrase.zh
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                if (currentLanguage != "zh") {
                    Text(
                        text = "${phrase.order}. ${phrase.zh}",
                        style = MaterialTheme.typography.caption1,
                        color = Color.Gray,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.body1,
                        color = Color.White,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = "${phrase.order}. $displayText",
                        style = MaterialTheme.typography.body1,
                        color = Color.White,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    @Composable
    fun LanguagePicker(
        options: List<String>,
        selectedIndex: Int,
        onLanguageSelected: (Int) -> Unit,
        modifier: Modifier = Modifier
    ) {
        // 創建 PickerState
        val pickerState = rememberPickerState(
            initialNumberOfOptions = options.size,
            initiallySelectedOption = selectedIndex
        )

        // 使用 LaunchedEffect 監聽 selectedOption 的變化
        LaunchedEffect(pickerState.selectedOption) {
            onLanguageSelected(pickerState.selectedOption)
        }

        // 使用新的 Picker API
        androidx.wear.compose.material.Picker(
            state = pickerState,
            modifier = modifier,
            contentDescription = "語言選擇器",
            separation = 8.dp,
            option = { optionIndex ->
                Text(
                    text = options[optionIndex],
                    style = MaterialTheme.typography.body1,
                    color = if (optionIndex == pickerState.selectedOption) Color.White else Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp)
                )
            }
        )
    }

    private fun speakText(text: String, currentLanguage: String) {
        if (::tts.isInitialized) {
            // 根據 currentLanguage 設定 TTS 的語言
            val locale = when (currentLanguage) {
                "zh" -> Locale.TRADITIONAL_CHINESE
                "en" -> Locale.ENGLISH
                "jp" -> Locale.JAPANESE
                "th" -> Locale("th")
                else -> Locale.TRADITIONAL_CHINESE
            }

            val result = tts.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("WearApp", "TTS 不支援所選語言")
                Toast.makeText(this, "TTS 不支援所選語言", Toast.LENGTH_SHORT).show()
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts1")
            }
        } else {
            Log.e("WearApp", "TTS 尚未初始化")
            Toast.makeText(this, "TTS 尚未初始化", Toast.LENGTH_SHORT).show()
        }
    }
}