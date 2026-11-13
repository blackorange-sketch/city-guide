package com.cityguide

import android.content.Context
import com.cityguide.data.LatLon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** Завантаження тайлів карти навколо маршруту, щоб карта працювала без інтернету. */
object OfflineMaps {
    private const val MARGIN_DEG = 0.01
    private const val MIN_ZOOM = 10.0
    private const val MAX_ZOOM = 16.0

    /** [onDone] викликається один раз: true — карта завантажена, false — помилка. */
    fun download(
        context: Context,
        styleUrl: String,
        points: List<LatLon>,
        name: String,
        onDone: (Boolean) -> Unit
    ) {
        if (points.isEmpty()) {
            onDone(false)
            return
        }
        val bounds = LatLngBounds.Builder()
            .include(LatLng(points.minOf { it.lat } - MARGIN_DEG, points.minOf { it.lon } - MARGIN_DEG))
            .include(LatLng(points.maxOf { it.lat } + MARGIN_DEG, points.maxOf { it.lon } + MARGIN_DEG))
            .build()
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl, bounds, MIN_ZOOM, MAX_ZOOM, context.resources.displayMetrics.density
        )
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            onDone(ok)
        }
        OfflineManager.getInstance(context).createOfflineRegion(
            definition,
            name.toByteArray(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            if (status.isComplete) finish(true)
                        }

                        override fun onError(error: OfflineRegionError) = finish(false)

                        override fun mapboxTileCountLimitExceeded(limit: Long) = finish(false)
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) = finish(false)
            }
        )
    }
}
