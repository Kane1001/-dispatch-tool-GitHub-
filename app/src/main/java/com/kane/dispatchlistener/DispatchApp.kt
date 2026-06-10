package com.kane.dispatchlistener

import android.app.Application

class DispatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OrderQueue.restore(this)
    }
}
