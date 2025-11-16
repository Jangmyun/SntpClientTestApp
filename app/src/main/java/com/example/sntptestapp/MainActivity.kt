package com.example.sntptestapp

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.exoplayer.util.SntpClient
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var btnSync: Button
    private lateinit var tvLocalTime: TextView
    private lateinit var tvNtpTime: TextView
    private lateinit var tvOffset: TextView
    private lateinit var tvStatus: TextView

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var loader: Loader? = null

    companion object {
        private const val TAG = "NtpSyncApp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)
            initViews()
            setupClickListeners()
            startLocalTimeUpdate()
            Log.d(TAG, "onCreate completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            throw e
        }
    }

    private fun initViews() {
        try {
            btnSync = findViewById(R.id.btnSync)
            tvLocalTime = findViewById(R.id.tvLocalTime)
            tvNtpTime = findViewById(R.id.tvNtpTime)
            tvOffset = findViewById(R.id.tvOffset)
            tvStatus = findViewById(R.id.tvStatus)
            Log.d(TAG, "All views initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing views", e)
            throw e
        }
    }

    private fun setupClickListeners() {
        btnSync.setOnClickListener {
            Log.d(TAG, "Sync button clicked")
            syncWithNtpServer()
        }
    }

    private fun startLocalTimeUpdate() {
        lifecycleScope.launch {
            try {
                while (true) {
                    updateLocalTime()
                    kotlinx.coroutines.delay(100)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in local time update", e)
            }
        }
    }

    private fun updateLocalTime() {
        try {
            val currentTime = System.currentTimeMillis()
            tvLocalTime.text = "로컬 시간: ${dateFormat.format(Date(currentTime))}"
        } catch (e: Exception) {
            Log.e(TAG, "Error updating local time", e)
        }
    }

    private fun syncWithNtpServer() {
        try {
            btnSync.isEnabled = false

            // NTP 호스트 가져오기 (null 체크)
            val ntpHost = try {
                SntpClient.getNtpHost() ?: "time.android.com"
            } catch (e: Exception) {
                Log.e(TAG, "Error getting NTP host", e)
                "NTP 서버"
            }

            tvStatus.text = "상태: $ntpHost 서버에 연결 중..."
            tvNtpTime.text = "NTP 시간: 대기 중..."
            tvOffset.text = "시간 차이: -"

            Log.d(TAG, "Starting NTP sync with $ntpHost")

            // 이전 loader가 있으면 release
            loader?.release()

            // 새 Loader 생성 (메인 스레드에서)
            loader = Loader("NtpLoader")

            val currentLoader = loader
            if (currentLoader == null) {
                Log.e(TAG, "Failed to create Loader")
                displayNtpResult(false, IOException("Loader 생성 실패"))
                btnSync.isEnabled = true
                return
            }

            SntpClient.initialize(currentLoader, object : SntpClient.InitializationCallback {
                override fun onInitialized() {
                    Log.d(TAG, "NTP initialization successful")
                    runOnUiThread {
                        displayNtpResult(true, null)
                        btnSync.isEnabled = true
                    }
                }

                override fun onInitializationFailed(error: IOException) {
                    Log.e(TAG, "NTP initialization failed", error)
                    runOnUiThread {
                        displayNtpResult(false, error)
                        btnSync.isEnabled = true
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncWithNtpServer", e)
            displayNtpResult(false, IOException("동기화 오류: ${e.message}"))
            btnSync.isEnabled = true
        }
    }

    private fun displayNtpResult(success: Boolean, error: IOException?) {
        try {
            if (success) {
                if (!SntpClient.isInitialized()) {
                    // ... (기존 초기화 체크 코드) ...
                    return
                }

                // [수정 1] offsetMs는 '시간 차이'가 아니라 'NTP 기준 부팅 시간'입니다.
                // (이름이 매우 오해하기 쉽습니다)
                // ntpBootTimeMs = serverTime(Unix) - responseElapsedRealtime
                val ntpBootTimeMs = SntpClient.getElapsedRealtimeOffsetMs()
                Log.d(TAG, "NTP Boot Time: $ntpBootTimeMs ms")

                if (ntpBootTimeMs == C.TIME_UNSET) {
                    // ... (기존 TIME_UNSET 체크 코드) ...
                    return
                }

                // ===== [수정 2] 올바른 시간 계산 =====

                // 1. 현재 부팅 후 경과 시간
                val elapsedRealtime = SystemClock.elapsedRealtime()

                // 2. NTP 현재 시각 계산
                // (NTP 현재 시각 = NTP 기준 부팅 시간 + 현재 부팅 후 경과 시간)
                val ntpCurrentTime = ntpBootTimeMs + elapsedRealtime

                // 3. 로컬 시간 (비교용)
                val localTime = System.currentTimeMillis()

                // 4. 실제 시간 차이(Drift) 계산
                // (시간 차이 = NTP 시각 - 로컬 시각)
                val timeDifferenceMs = ntpCurrentTime - localTime


                val ntpHost = try {
                    SntpClient.getNtpHost() ?: "NTP 서버"
                } catch (e: Exception) {
                    "NTP 서버"
                }

                tvStatus.text = "상태: $ntpHost 동기화 완료"

                // ntpCurrentTime을 사용하도록 수정
                tvNtpTime.text = "NTP 시간: ${dateFormat.format(Date(ntpCurrentTime))}"

                // [수정 3] 시간 차이(Drift)를 표시
                val offsetSeconds = timeDifferenceMs / 1000.0
                val offsetText = when {
                    offsetSeconds > 0 -> "+${String.format("%.3f", offsetSeconds)}초 (로컬이 느림)"
                    offsetSeconds < 0 -> "${String.format("%.3f", offsetSeconds)}초 (로컬이 빠름)"
                    else -> "0초 (정확히 일치)"
                }

                tvOffset.text = """
                시간 차이: $offsetText
                Drift: ${timeDifferenceMs}ms
                NTP BootTime: ${ntpBootTimeMs}ms
                서버: $ntpHost
            """.trimIndent()

                Log.d(TAG, "Local: ${dateFormat.format(Date(localTime))}")
                Log.d(TAG, "NTP: ${dateFormat.format(Date(ntpCurrentTime))}")
                Log.d(TAG, "Drift: ${timeDifferenceMs}ms")
                Log.d(TAG, "NTP result displayed successfully")
            } else {
                // ... (기존 실패 처리 로직) ...
            }
        } catch (e: Exception) {
            // ... (기존 예외 처리 로직) ...
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            loader?.release()
            Log.d(TAG, "Loader released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing loader", e)
        }
    }
}