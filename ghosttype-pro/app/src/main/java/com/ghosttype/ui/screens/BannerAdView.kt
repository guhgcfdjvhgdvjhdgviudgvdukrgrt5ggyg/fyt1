package com.ghosttype.ui.screens

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

@Composable
fun rememberInterstitialAd(
    adUnitId: String = "ca-app-pub-3606781266887428/2741608788",
    onAdDismissed: () -> Unit = {}
): InterstitialAdHandle {
    val context = LocalContext.current
    val handle = remember {
        InterstitialAdHandle(context as Activity, adUnitId, onAdDismissed)
    }

    DisposableEffect(Unit) {
        handle.load()
        onDispose { }
    }

    return handle
}

class InterstitialAdHandle(
    private val activity: Activity,
    private val adUnitId: String,
    private val onAdDismissed: () -> Unit
) {
    private var interstitialAd: InterstitialAd? = null

    fun load() {
        InterstitialAd.load(activity, adUnitId, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            interstitialAd = null
                            load()
                            onAdDismissed()
                        }

                        override fun onAdFailedToShowFullScreenContent(p0: com.google.android.gms.ads.AdError) {
                            interstitialAd = null
                        }
                    }
                }

                override fun onAdFailedToLoad(p0: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }

    fun show() {
        interstitialAd?.show(activity)
    }

    fun isLoaded(): Boolean = interstitialAd != null
}
