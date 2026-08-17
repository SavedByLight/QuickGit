package com.quickgit.desktop.data

object AppLog {
    fun i(tag: String, msg: String) = println("I/$tag: $msg")
    fun w(tag: String, msg: String) = System.err.println("W/$tag: $msg")
    fun e(tag: String, msg: String, t: Throwable? = null) {
        System.err.println("E/$tag: $msg")
        t?.printStackTrace()
    }
}
