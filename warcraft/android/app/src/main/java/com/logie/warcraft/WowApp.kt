// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.app.Application

/** The app: in its own process (not the realm's, [RealmLink]), it hears the realm's server from the start. */
class WowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RealmLink.attach(this)
    }
}
