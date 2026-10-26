package com.limpe.tengerium.util

/** True while the main activity is visible. Lets background-only notifications stay quiet. */
object AppForeground {
    @Volatile
    var visible: Boolean = false
}
