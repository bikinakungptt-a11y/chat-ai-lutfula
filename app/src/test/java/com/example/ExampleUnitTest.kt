package com.example

import org.junit.Assert.*
import org.junit.Test
import com.example.network.ChatRequest
import com.example.network.ChatMessage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class ExampleUnitTest {
  @Test
  fun moshiTest() {
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val request = ChatRequest(model = "gpt-4", messages = listOf(), reasoning = null)
    val json = moshi.adapter(ChatRequest::class.java).toJson(request)
    println("JSON OUTPUT: $json")
  }
}
