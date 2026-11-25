package com.restalker.mobile.core

/** 1.1.8: a notice from Restalker to every device (the relay keeps the newest one; each has a number that only goes up). */
object Notices {
    /** Shown when its number is higher than the last one shown here (each notice once, and never an older one again). */
    fun isNew(id: Long, seen: Long, title: String): Boolean = id > seen && id > 0 && title.trim().length >= 3
}
