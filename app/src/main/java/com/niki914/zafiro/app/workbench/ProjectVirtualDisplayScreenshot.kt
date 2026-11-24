package com.niki914.zafiro.app.workbench

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.Q)
internal suspend fun saveVirtualDisplayPng(resolver: ContentResolver, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Zafiro-" + UUID.randomUUID() + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Zafiro")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return@withContext false
        var published = false
        try {
            val written = resolver.openOutputStream(uri, "w")?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (written != true) return@withContext false
            coroutineContext.ensureActive()
            val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            published = resolver.update(uri, ready, null, null) == 1
            published
        } finally {
            if (!published) runCatching { resolver.delete(uri, null, null) }
        }
    }
