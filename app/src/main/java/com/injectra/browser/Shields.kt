package com.injectra.browser

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Built-in ad and tracker blocker. Subresource requests whose host is (or is a
 * subdomain of) a listed domain get an empty response instead of hitting the
 * network. Main-frame navigations are never blocked.
 */
object Shields {

    private val BLOCKED = hashSetOf(
        // Google ads / analytics
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adservice.google.com", "pagead2.googlesyndication.com", "imasdk.googleapis.com",
        "app-measurement.com", "2mdn.net",
        // Meta / social trackers
        "connect.facebook.net", "pixel.facebook.com", "an.facebook.com",
        "ads.linkedin.com", "px.ads.linkedin.com", "analytics.twitter.com",
        "ads-twitter.com", "static.ads-twitter.com", "ads.pinterest.com",
        "ct.pinterest.com", "analytics.tiktok.com", "ads.tiktok.com",
        "sc-static.net", "tr.snapchat.com", "ads.reddit.com", "alb.reddit.com",
        // Amazon / Microsoft ads
        "amazon-adsystem.com", "aax.amazon-adsystem.com", "assoc-amazon.com",
        "bat.bing.com", "ads.microsoft.com", "clarity.ms",
        // Ad exchanges & SSPs
        "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
        "openx.net", "casalemedia.com", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "revcontent.com", "mgid.com",
        "smartadserver.com", "adform.net", "yieldmo.com", "sharethrough.com",
        "triplelift.com", "33across.com", "indexww.com", "contextweb.com",
        "sovrn.com", "lijit.com", "gumgum.com", "teads.tv", "media.net",
        "advertising.com", "adtechus.com", "bidswitch.net", "spotxchange.com",
        "springserve.com", "smaato.net", "inmobi.com", "mopub.com",
        "applovin.com", "unityads.unity3d.com", "adcolony.com", "vungle.com",
        "chartboost.com", "ironsrc.com", "supersonicads.com", "zedo.com",
        "propellerads.com", "popads.net", "popcash.net", "adsterra.com",
        "exoclick.com", "juicyads.com", "trafficjunky.net", "hilltopads.net",
        "adcash.com", "admaven.com", "onclickads.net", "clickadu.com",
        "yllix.com", "a-ads.com", "bidvertiser.com", "infolinks.com",
        "adroll.com", "quantcast.com", "quantserve.com", "lotame.com",
        "crwdcntrl.net", "bluekai.com", "demdex.net", "omtrdc.net",
        "everesttech.net", "rlcdn.com", "agkn.com", "adsymptotic.com",
        "tapad.com", "eyeota.net", "exelator.com", "mathtag.com",
        "turn.com", "serving-sys.com", "flashtalking.com", "innovid.com",
        "moatads.com", "doubleverify.com", "adsafeprotected.com",
        "iasds01.com", "zemanta.com", "nativo.com", "adblade.com",
        // Analytics / session replay / fingerprinting
        "scorecardresearch.com", "comscore.com", "chartbeat.com",
        "chartbeat.net", "hotjar.com", "hotjar.io", "mouseflow.com",
        "fullstory.com", "crazyegg.com", "luckyorange.com", "inspectlet.com",
        "mixpanel.com", "amplitude.com", "heapanalytics.com", "segment.io",
        "kissmetrics.com", "newrelic.com", "nr-data.net", "optimizely.com",
        "branch.io", "app.link", "adjust.com", "appsflyer.com", "kochava.com",
        "singular.net", "statcounter.com", "histats.com",
        "mc.yandex.ru", "top-fwz1.mail.ru", "counter.yadro.ru",
        "fingerprintjs.com", "fpjs.io", "openfpcdn.io", "iovation.com",
        "threatmetrix.com", "parsely.com", "parse.ly", "cxense.com",
        "permutive.com", "permutive.app", "id5-sync.com", "liveramp.com",
        "pippio.com", "addthis.com", "sharethis.com", "disqusads.com",
        "onesignal.com", "pushcrew.com", "pushengage.com", "subscribers.com"
    )

    /** True if [host] or any of its parent domains is on the blocklist. */
    fun isBlocked(host: String): Boolean {
        var h = host.lowercase()
        while (true) {
            if (h in BLOCKED) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.lastIndexOf('.')) return false
            h = h.substring(dot + 1)
        }
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
