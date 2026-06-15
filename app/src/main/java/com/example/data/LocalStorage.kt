package com.example.data

import android.content.Context
import android.content.SharedPreferences

class LocalStorage(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("localStorage", Context.MODE_PRIVATE)

    init {
        // Requirement 2: Save this instruction permanently
        val current = getInstruction()
        if (current.isEmpty()) {
            saveInstruction("Do not use markdown bold, italic, bullet star symbols, or asterisk characters in any assistant response.")
        }
    }

    fun saveInstruction(instruction: String): Boolean {
        val current = getInstruction()
        val newInst = if (current.isEmpty()) instruction else "$current\n$instruction"
        return prefs.edit().putString("custom_instruction", newInst).commit()
    }

    fun getInstruction(): String {
        return prefs.getString("custom_instruction", "") ?: ""
    }
}
