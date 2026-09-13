package ru.evrasia.research

import android.net.Uri

internal val Uri?.scheme: String?
    get() = this?.getScheme()

internal val Uri?.path: String?
    get() = this?.getPath()
