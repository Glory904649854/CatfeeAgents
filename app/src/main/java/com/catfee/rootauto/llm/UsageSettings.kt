package com.catfee.rootauto.llm

import android.content.Context

/** 用量面板的简单价格配置; 只用于估算, 不影响请求 */
object UsageSettings {
    private const val PREF = "catfee_usage_pref"

    data class Price(val inputPerMillion: Double = 0.0, val outputPerMillion: Double = 0.0)

    fun load(context: Context): Price {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Price(
            inputPerMillion = p.getString("input", "0")?.toDoubleOrNull() ?: 0.0,
            outputPerMillion = p.getString("output", "0")?.toDoubleOrNull() ?: 0.0
        )
    }

    fun save(context: Context, input: Double, output: Double) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("input", input.toString())
            .putString("output", output.toString())
            .apply()
    }
}
