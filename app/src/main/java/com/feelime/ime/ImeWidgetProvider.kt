package com.feelime.ime

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** One-cell home-screen entry that shares the transparent picker hand-off. */
class ImeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = buildViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    companion object {
        fun requestPin(context: Context): Boolean {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return false
            val manager = AppWidgetManager.getInstance(context)
            return manager.requestPinAppWidget(
                ComponentName(context, ImeWidgetProvider::class.java),
                null,
                null,
            )
        }

        private fun buildViews(context: Context): RemoteViews {
            val intent = Intent(context, ImePickerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            val pending = PendingIntent.getActivity(
                context,
                0xF31F,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return RemoteViews(context.packageName, R.layout.widget_ime).apply {
                setOnClickPendingIntent(R.id.widget_root, pending)
            }
        }
    }
}

