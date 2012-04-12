/*
 * Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.apps.mytracks.util;

import com.google.android.apps.analytics.GoogleAnalyticsTracker;
import com.google.android.maps.mytracks.R;

import android.content.Context;

/**
 * Utitlites for sending pageviews to Google Analytics.
 *
 * @author Jimmy Shih
 */
public class AnalyticsUtils {

  private AnalyticsUtils() {}

  public static void sendPageViews(Context context, String ... pages) {
    GoogleAnalyticsTracker tracker = GoogleAnalyticsTracker.getInstance();
    tracker.start(context.getString(R.string.my_tracks_analytics_id), context);
    tracker.setProductVersion("android-mytracks", SystemUtils.getMyTracksVersion(context));
    for (String page : pages) {
      tracker.trackPageView(page);
    }
    tracker.dispatch();
    tracker.stop();
  }
}
