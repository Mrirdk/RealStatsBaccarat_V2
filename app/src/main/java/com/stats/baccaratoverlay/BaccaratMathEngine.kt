package com.stats.baccaratoverlay

import java.util.Locale
import kotlin.math.sqrt

data class AutoPredictionReport(
    val lastCapturedLabel: String,
    val robotBadgeText: String,
    val mainRecommendation: String,
    val recommendedSide: String, // "P", "B", or "WAIT"
    val realDistributionText: String,
    val markovProbabilityText: String,
    val recentSequenceText: String
)

object BaccaratMathEngine {

    // Xác suất chuẩn Baccarat 8 bộ bài (không tính Hòa)
    private const val TRUE_BANKER_PROB = 0.5068
    private const val TRUE_PLAYER_PROB = 0.4932

    // Ngưỡng lọc tối thiểu để xuất lệnh (tránh vào cầu 50/50 nhiễu)
    private const val MIN_CONFIDENCE_THRESHOLD = 57.5

    fun evaluate(rawHistory: List<String>): AutoPredictionReport {
        val total = rawHistory.size
        if (total == 0) {
            return AutoPredictionReport(
                lastCapturedLabel = "Vừa bắt được: Chờ ván mới...",
                robotBadgeText = "AUTO: ĐANG SOI",
                mainRecommendation = "ĐẶT TÂM 🎯 VÀO VÙNG KẾT QUẢ",
                recommendedSide = "WAIT",
                realDistributionText = "Tỷ lệ thực bàn (0 ván): P 0.0% | B 0.0%",
                markovProbabilityText = "Xác suất nhịp thực: Chờ dữ liệu bàn",
                recentSequenceText = "Chuỗi bắt được: Trống"
            )
        }

        val lastRaw = rawHistory.last()
        val lastDesc = when (lastRaw) {
            "P" -> "🔵 CON (PLAYER)"
            "B" -> "🔴 CÁI (BANKER)"
            else -> "🟢 HÒA (TIE)"
        }

        val pCount = rawHistory.count { it == "P" }
        val bCount = rawHistory.count { it == "B" }
        val tCount = rawHistory.count { it == "T" }

        val pTotalRate = (pCount.toDouble() / total) * 100.0
        val bTotalRate = (bCount.toDouble() / total) * 100.0

        val distText = String.format(
            Locale.US,
            "Tỷ lệ thực (%d ván): P %.1f%% (%d) | B %.1f%% (%d) | T %d",
            total, pTotalRate, pCount, bTotalRate, bCount, tCount
        )

        val pbHistory = rawHistory.filter { it == "P" || it == "B" }
        val pbTotal = pbHistory.size

        if (pbTotal == 0) {
            return AutoPredictionReport(
                lastCapturedLabel = "Vừa bắt được: $lastDesc",
                robotBadgeText = "CHỜ P/B",
                mainRecommendation = "⏸ CHỜ KẾT QUẢ CON HOẶC CÁI",
                recommendedSide = "WAIT",
                realDistributionText = distText,
                markovProbabilityText = "Xác suất nhịp thực: Chưa có P/B",
                recentSequenceText = "Chuỗi bắt được: ${rawHistory.takeLast(10).joinToString("-")}"
            )
        }

        val lastSide = pbHistory.last()
        var streak = 0
        for (i in pbHistory.indices.reversed()) {
            if (pbHistory[i] == lastSide) streak++ else break
        }

        val recentSeqText = "Chuỗi (${pbHistory.size} tay): ${pbHistory.takeLast(10).joinToString("-")} (Dây $streak)"

        // Cần tối thiểu 3 tay P/B thực tế để bắt đầu tính xác suất có điều kiện
        if (pbTotal < 3) {
            return AutoPredictionReport(
                lastCapturedLabel = "Vừa bắt được: $lastDesc",
                robotBadgeText = "SOI (${pbTotal}/3)",
                mainRecommendation = "⏳ ĐANG THU THẬP DATA (${pbTotal}/3 TAY)",
                recommendedSide = "WAIT",
                realDistributionText = distText,
                markovProbabilityText = "Cần đủ 3 tay P/B thực tế để tính tỷ lệ",
                recentSequenceText = recentSeqText
            )
        }

        // 1. Tính xác suất chuyển trạng thái Markov bậc 1 từ dữ liệu thực
        var followP1 = 0
        var followB1 = 0
        for (i in 0 until pbHistory.size - 1) {
            if (pbHistory[i] == lastSide) {
                if (pbHistory[i + 1] == "P") followP1++
                else if (pbHistory[i + 1] == "B") followB1++
            }
        }
        val sample1 = followP1 + followB1

        // 2. Tính xác suất khớp mẫu 2 tay gần nhất (Markov bậc 2)
        var followP2 = 0
        var followB2 = 0
        val s1 = pbHistory[pbHistory.size - 2]
        val s2 = pbHistory[pbHistory.size - 1]
        for (i in 0 until pbHistory.size - 2) {
            if (pbHistory[i] == s1 && pbHistory[i + 1] == s2) {
                if (pbHistory[i + 2] == "P") followP2++
                else if (pbHistory[i + 2] == "B") followB2++
            }
        }
        val sample2 = followP2 + followB2

        // 3. Tính Z-Score độ lệch hộp bài thực tế
        val expectedB = pbTotal * TRUE_BANKER_PROB
        val stdDev = sqrt(pbTotal * TRUE_BANKER_PROB * TRUE_PLAYER_PROB)
        val zScore = if (stdDev > 0) (bCount - expectedB) / stdDev else 0.0

        // Chọn bộ mẫu thực tế có độ tin cậy cao nhất
        val probP: Double
        val probB: Double
        val markovDetail: String

        if (sample2 >= 2) {
            probP = (followP2.toDouble() / sample2) * 100.0
            probB = (followB2.toDouble() / sample2) * 100.0
            markovDetail = String.format(
                Locale.US,
                "Nhịp [%s-%s] thực tế: P %.1f%% (%d/%d) | B %.1f%% (%d/%d)",
                s1, s2, probP, followP2, sample2, probB, followB2, sample2
            )
        } else if (sample1 >= 2) {
            probP = (followP1.toDouble() / sample1) * 100.0
            probB = (followB1.toDouble() / sample1) * 100.0
            markovDetail = String.format(
                Locale.US,
                "Sau [%s] thực tế: P %.1f%% (%d/%d) | B %.1f%% (%d/%d)",
                lastSide, probP, followP1, sample1, probB, followB1, sample1
            )
        } else {
            // Khi chưa lặp lại mẫu chuyển tiếp, dùng tỷ lệ phân phối thực của bàn
            probP = (pCount.toDouble() / pbTotal) * 100.0
            probB = (bCount.toDouble() / pbTotal) * 100.0
            markovDetail = String.format(
                Locale.US,
                "Tỷ lệ nghiêng bàn thực: P %.1f%% | B %.1f%% (Z=%.2f)",
                probP, probB, zScore
            )
        }

        // Bộ lọc ngưỡng thực tế: Chỉ báo Con/Cái khi tỷ lệ thực vượt ngưỡng lọc
        return when {
            probP >= MIN_CONFIDENCE_THRESHOLD && probP > probB -> {
                val pct = String.format(Locale.US, "%.1f%%", probP)
                AutoPredictionReport(
                    lastCapturedLabel = "Vừa bắt được: $lastDesc",
                    robotBadgeText = "CON $pct",
                    mainRecommendation = "🔵 CHỐT: CON (PLAYER) — $pct",
                    recommendedSide = "P",
                    realDistributionText = distText,
                    markovProbabilityText = markovDetail,
                    recentSequenceText = recentSeqText
                )
            }
            probB >= MIN_CONFIDENCE_THRESHOLD && probB > probP -> {
                val pct = String.format(Locale.US, "%.1f%%", probB)
                AutoPredictionReport(
                    lastCapturedLabel = "Vừa bắt được: $lastDesc",
                    robotBadgeText = "CÁI $pct",
                    mainRecommendation = "🔴 CHỐT: CÁI (BANKER) — $pct",
                    recommendedSide = "B",
                    realDistributionText = distText,
                    markovProbabilityText = markovDetail,
                    recentSequenceText = recentSeqText
                )
            }
            else -> {
                AutoPredictionReport(
                    lastCapturedLabel = "Vừa bắt được: $lastDesc",
                    robotBadgeText = "BỎ QUA",
                    mainRecommendation = "⏸ BỎ QUA (Tỷ lệ 50/50 chưa đạt ngưỡng)",
                    recommendedSide = "WAIT",
                    realDistributionText = distText,
                    markovProbabilityText = markovDetail,
                    recentSequenceText = recentSeqText
                )
            }
        }
    }
}
