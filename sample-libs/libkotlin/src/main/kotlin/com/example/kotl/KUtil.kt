package com.example.kotl

object KSingleton {
    val answer: Int = 42
}

class KUtil {
    fun makePoint(): KPoint = KPoint(1, 2)
}
