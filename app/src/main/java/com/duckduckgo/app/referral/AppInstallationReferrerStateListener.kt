/*
 * Copyright (c) 2019 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.referral

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse.*
import com.android.installreferrer.api.InstallReferrerStateListener
import com.duckduckgo.app.playstore.PlayStoreAndroidUtils.Companion.PLAY_STORE_PACKAGE
import com.duckduckgo.app.playstore.PlayStoreAndroidUtils.Companion.PLAY_STORE_REFERRAL_SERVICE
import com.duckduckgo.app.referral.ParseFailureReason.*
import com.duckduckgo.app.referral.ParsedReferrerResult.*
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject

interface AppInstallationReferrerStateListener {

    fun initialiseReferralRetrieval()
    suspend fun retrieveReferralCode(): ParsedReferrerResult

}

class PlayStoreAppReferrerStateListener @Inject constructor(
    val context: Context,
    private val packageManager: PackageManager,
    private val appInstallationReferrerParser: AppInstallationReferrerParser,
    private val appReferrerDataStore: AppReferrerDataStore
) : InstallReferrerStateListener, AppInstallationReferrerStateListener {

    private val referralClient = InstallReferrerClient.newBuilder(context).build()
    private var initialisationStartTime: Long = 0

    private var referralResult: ParsedReferrerResult = ReferrerInitialising

    /**
     * Initialises the referrer service. This should only be called once.
     */
    override fun initialiseReferralRetrieval() {
        try {
            initialisationStartTime = System.currentTimeMillis()

            if (appReferrerDataStore.referrerCheckedPreviously) {
                referralResult = loadPreviousReferrerData()
                Timber.i("Already inspected this referrer data. Took ${System.currentTimeMillis() - initialisationStartTime}ms to load from disk")
                return
            }

            if (playStoreReferralServiceInstalled()) {
                referralClient.startConnection(this)
            } else {
                referralResult = ParseFailure(ReferralServiceUnavailable)
            }
        } catch (e: RuntimeException) {
            Timber.w(e, "Failed to obtain referrer information")
            referralResult = ParseFailure(UnknownError)
        }
    }

    private fun loadPreviousReferrerData(): ParsedReferrerResult {
        val suffix = loadFromDataStore()
        return if (suffix == null) {
            Timber.i("Already saw referrer data, but no campaign suffix saved")
            ReferrerNotFound(fromCache = true)
        } else {
            Timber.i("Already have referrer data from previous run - $suffix")
            ReferrerFound(suffix, fromCache = true)
        }
    }

    override fun onInstallReferrerSetupFinished(responseCode: Int) {
        val referrerRetrievalDurationMs = System.currentTimeMillis() - initialisationStartTime
        Timber.i("Took ${referrerRetrievalDurationMs}ms to get initial referral data callback")

        when (responseCode) {
            OK -> {
                Timber.d("Successfully connected to Referrer service")
                val response = referralClient.installReferrer
                val referrer = response.installReferrer
                val parsedResult = appInstallationReferrerParser.parse(referrer)
                referralResultReceived(parsedResult)
            }
            FEATURE_NOT_SUPPORTED -> referralResultFailed(FeatureNotSupported)
            SERVICE_UNAVAILABLE -> referralResultFailed(ServiceUnavailable)
            DEVELOPER_ERROR -> referralResultFailed(DeveloperError)
            SERVICE_DISCONNECTED -> referralResultFailed(ServiceDisconnected)
            else -> referralResultFailed(UnknownError)
        }
        referralClient.endConnection()
    }

    /**
     * Retrieves the app installation referral code.
     * This might return a result immediately or might wait for a result to become available. There is no guarantee that a result will ever be returned.
     *
     * It is the caller's responsibility to guard against this function not returning a result in a timely manner, or not returning a result ever.
     */
    override suspend fun retrieveReferralCode(): ParsedReferrerResult {
        if (referralResult != ReferrerInitialising) {
            Timber.i("Referrer already determined; immediately answering")
            return referralResult
        }

        Timber.i("Referrer: Retrieving referral code from Play Store referrer service")

        // poll, awaiting referral result to become available
        while (referralResult == ReferrerInitialising) {
            Timber.v("Still initialising - waiting")
            delay(10)
        }

        return referralResult
    }

    private fun loadFromDataStore(): String? {
        return appReferrerDataStore.campaignSuffix
    }

    private fun playStoreReferralServiceInstalled(): Boolean {
        val playStoreConnectionServiceIntent = Intent()
        playStoreConnectionServiceIntent.component = ComponentName(PLAY_STORE_PACKAGE, PLAY_STORE_REFERRAL_SERVICE)
        val matchingServices = packageManager.queryIntentServices(playStoreConnectionServiceIntent, 0)
        return matchingServices.size > 0
    }

    private fun referralResultReceived(result: ParsedReferrerResult) {
        referralResult = result

        if (result is ReferrerFound) {
            appReferrerDataStore.campaignSuffix = result.campaignSuffix
        }
        appReferrerDataStore.referrerCheckedPreviously = true
    }

    private fun referralResultFailed(reason: ParseFailureReason) {
        referralResult = ParseFailure(reason)
    }

    override fun onInstallReferrerServiceDisconnected() {
        Timber.i("Referrer: ServiceDisconnected")
    }
}