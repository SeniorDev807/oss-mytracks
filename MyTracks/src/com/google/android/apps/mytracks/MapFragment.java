/*
 * Copyright 2008 Google Inc.
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

package com.google.android.apps.mytracks;

import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.MyTracksProviderUtils.Factory;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.content.TrackDataHub;
import com.google.android.apps.mytracks.content.TrackDataHub.ListenerDataType;
import com.google.android.apps.mytracks.content.TrackDataListener;
import com.google.android.apps.mytracks.content.Waypoint;
import com.google.android.apps.mytracks.stats.TripStatistics;
import com.google.android.apps.mytracks.util.GeoRect;
import com.google.android.apps.mytracks.util.LocationUtils;
import com.google.android.maps.GeoPoint;
import com.google.android.maps.MapController;
import com.google.android.maps.MapView;
import com.google.android.maps.mytracks.R;

import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.EnumSet;

/**
 * A fragment to display map to the user.
 *
 * @author Leif Hendrik Wilden
 * @author Rodrigo Damazio
 */
public class MapFragment extends Fragment
    implements View.OnTouchListener, View.OnClickListener, TrackDataListener {

  private static final String KEY_CURRENT_LOCATION = "currentLocation";
  private static final String KEY_KEEP_MY_LOCATION_VISIBLE = "keepMyLocationVisible";

  private TrackDataHub trackDataHub;

  // True to keep my location visible.
  private boolean keepMyLocationVisible;

  // True to zoom to my location. Only apply when keepMyLocationVisible is true.
  private boolean zoomToMyLocation;

  // The track id of the waypoint to show.
  private long waypointTrackId;

  // The waypoint id to show
  private long waypointId;

  // The current selected track id. Set in onSelectedTrackChanged.
  private long currentSelectedTrackId;

  // The current location. Set in onCurrentLocationChanged.
  private Location currentLocation;

  // UI elements
  private View mapViewContainer;
  private MapOverlay mapOverlay;
  private RelativeLayout screen;
  private MapView mapView;
  private LinearLayout messagePane;
  private TextView messageText;
  private LinearLayout busyPane;

  @Override
  public View onCreateView(
      LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    mapViewContainer = ((TrackDetailActivity) getActivity()).getMapViewContainer();

    mapOverlay = new MapOverlay(getActivity());
    
    screen = (RelativeLayout) mapViewContainer.findViewById(R.id.screen);
    mapView = (MapView) mapViewContainer.findViewById(R.id.map);
    mapView.requestFocus();
    mapView.setOnTouchListener(this);
    mapView.setBuiltInZoomControls(true);
    mapView.getOverlays().add(mapOverlay);
    messagePane = (LinearLayout) mapViewContainer.findViewById(R.id.messagepane);
    messageText = (TextView) mapViewContainer.findViewById(R.id.messagetext);
    busyPane = (LinearLayout) mapViewContainer.findViewById(R.id.busypane);

    return mapViewContainer;
  }

  @Override
  public void onActivityCreated(Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    if (savedInstanceState != null) {
      keepMyLocationVisible = savedInstanceState.getBoolean(KEY_KEEP_MY_LOCATION_VISIBLE, false);
      currentLocation = (Location) savedInstanceState.getParcelable(KEY_CURRENT_LOCATION);
      if (currentLocation != null) {
        updateCurrentLocation();
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    resumeTrackDataHub();
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(KEY_KEEP_MY_LOCATION_VISIBLE, keepMyLocationVisible);
    if (currentLocation != null) {
      outState.putParcelable(KEY_CURRENT_LOCATION, currentLocation);
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    pauseTrackDataHub();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    ViewGroup parentViewGroup = (ViewGroup) mapViewContainer.getParent();
    if (parentViewGroup != null) {
      parentViewGroup.removeView(mapViewContainer);
    }
  }

  /**
   * Shows my location.
   */
  public void showMyLocation() {
    updateTrackDataHub();
    keepMyLocationVisible = true;
    zoomToMyLocation = true;
    if (currentLocation != null) {
      updateCurrentLocation();
    }
  }

  /**
   * Shows the waypoint.
   * 
   * @param id the waypoint id
   */
  public void showWaypoint(long id) {
    MyTracksProviderUtils MyTracksProviderUtils = Factory.get(getActivity());
    Waypoint waypoint = MyTracksProviderUtils.getWaypoint(id);
    if (waypoint != null && waypoint.getLocation() != null) {
      keepMyLocationVisible = false;
      GeoPoint center = new GeoPoint((int) (waypoint.getLocation().getLatitude() * 1E6),
          (int) (waypoint.getLocation().getLongitude() * 1E6));
      mapView.getController().setCenter(center);
      mapView.getController().setZoom(mapView.getMaxZoomLevel());
      mapView.invalidate();
    }
  }

  /**
   * Shows the waypoint.
   *
   * @param trackId the track id
   * @param id the waypoint id
   */
  public void showWaypoint(long trackId, long id) {
    /*
     * Synchronize to prevent race condition in changing waypointTrackId and
     * waypointId variables.
     */
    synchronized (this) {
      if (trackId == currentSelectedTrackId) {
        showWaypoint(id);
        waypointTrackId = -1L;
        waypointId = -1L;
        return;
      }
      waypointTrackId = trackId;
      waypointId = id;
    }
  }

  /**
   * Returns true if in satellite mode.
   */
  public boolean isSatelliteView() {
    return mapView.isSatellite();
  }

  /**
   * Sets the satellite mode
   * 
   * @param enabled true for satellite mode, false for map mode
   */
  public void setSatelliteView(boolean enabled) {
    mapView.setSatellite(enabled);
  }

  @Override
  public boolean onTouch(View view, MotionEvent event) {
    if (keepMyLocationVisible && event.getAction() == MotionEvent.ACTION_MOVE) {
      if (!isVisible(currentLocation)) {
        /*
         * Only set to false when no longer visible. Thus can keep showing the
         * current location with the next location update.
         */
        keepMyLocationVisible = false;
      }
    }
    return false;
  }

  @Override
  public void onClick(View v) {
    if (v == messagePane) {
      startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }
  }

  @Override
  public void onProviderStateChange(ProviderState state) {
    final int messageId;
    final boolean isGpsDisabled;
    switch (state) {
      case DISABLED:
        messageId = R.string.gps_need_to_enable;
        isGpsDisabled = true;
        break;
      case NO_FIX:
      case BAD_FIX:
        messageId = R.string.gps_wait_for_fix;
        isGpsDisabled = false;
        break;
      case GOOD_FIX:
        messageId = -1;
        isGpsDisabled = false;
        break;
      default:
        throw new IllegalArgumentException("Unexpected state: " + state);
    }

    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (messageId != -1) {
          messageText.setText(messageId);
          messagePane.setVisibility(View.VISIBLE);

          if (isGpsDisabled) {
            Toast.makeText(getActivity(), R.string.gps_not_found, Toast.LENGTH_LONG).show();

            // Click to show the location source settings
            messagePane.setOnClickListener(MapFragment.this);
          } else {
            messagePane.setOnClickListener(null);
          }
        } else {
          messagePane.setVisibility(View.GONE);
        }
        screen.requestLayout();
      }
    });
  }

  @Override
  public void onCurrentLocationChanged(Location location) {
    currentLocation = location;
    updateCurrentLocation();
  }

  @Override
  public void onCurrentHeadingChanged(double heading) {
    if (mapOverlay.setHeading((float) heading)) {
      mapView.postInvalidate();
    }
  }

  @Override
  public void onSelectedTrackChanged(final Track track, final boolean isRecording) {
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        boolean hasTrack = track != null;
        mapOverlay.setTrackDrawingEnabled(hasTrack);
  
        if (hasTrack) {
          busyPane.setVisibility(View.VISIBLE);
  
          synchronized (this) {
            /*
             * Synchronize to prevent race condition in changing waypointTrackId
             * and waypointId variables.
             */
            currentSelectedTrackId = track.getId();
            updateMap(track);
          }
          mapOverlay.setShowEndMarker(!isRecording);
          busyPane.setVisibility(View.GONE);
        }
        mapView.invalidate();
      }
    });
  }

  @Override
  public void onTrackUpdated(Track track) {
    // We don't care.
  }

  @Override
  public void clearTrackPoints() {
    mapOverlay.clearPoints();
  }

  @Override
  public void onNewTrackPoint(Location location) {
    if (LocationUtils.isValidLocation(location)) {
      mapOverlay.addLocation(location);
    }
  }

  @Override
  public void onSampledOutTrackPoint(Location loc) {
    // We don't care.
  }

  @Override
  public void onSegmentSplit() {
    mapOverlay.addSegmentSplit();
  }

  @Override
  public void onNewTrackPointsDone() {
    mapView.postInvalidate();
  }

  @Override
  public void clearWaypoints() {
    mapOverlay.clearWaypoints();
  }

  @Override
  public void onNewWaypoint(Waypoint waypoint) {
    if (waypoint != null && LocationUtils.isValidLocation(waypoint.getLocation())) {
      // TODO: Optimize locking inside addWaypoint
      mapOverlay.addWaypoint(waypoint);
    }
  }

  @Override
  public void onNewWaypointsDone() {
    mapView.postInvalidate();
  }

  @Override
  public boolean onUnitsChanged(boolean metric) {
    // We don't care.
    return false;
  }

  @Override
  public boolean onReportSpeedChanged(boolean reportSpeed) {
    // We don't care.
    return false;
  }
  
  /**
   * Resumes the trackDataHub. Needs to be synchronized because trackDataHub can be
   * accessed by multiple threads.
   */
  private synchronized void resumeTrackDataHub() {
    trackDataHub = ((MyTracksApplication) getActivity().getApplication()).getTrackDataHub();
    trackDataHub.registerTrackDataListener(this, EnumSet.of(
        ListenerDataType.SELECTED_TRACK_CHANGED,
        ListenerDataType.WAYPOINT_UPDATES,
        ListenerDataType.POINT_UPDATES,
        ListenerDataType.LOCATION_UPDATES,
        ListenerDataType.COMPASS_UPDATES));
  }
  
  /**
   * Pauses the trackDataHub. Needs to be synchronized because trackDataHub can be
   * accessed by multiple threads. 
   */
  private synchronized void pauseTrackDataHub() {
    trackDataHub.unregisterTrackDataListener(this);
    trackDataHub = null;
  }

  /**
   * Updates the trackDataHub. Needs to be synchronized because trackDataHub can be
   * accessed by multiple threads. 
   */
  private synchronized void updateTrackDataHub() { 
    trackDataHub.forceUpdateLocation();
  }
  
  /**
   * Updates the map by either zooming to the requested waypoint or showing the track.
   *
   * @param track the track
   */
  private void updateMap(Track track) {
    if (track.getId() == waypointTrackId) {
      // Show the waypoint
      showWaypoint(waypointId);

      waypointTrackId = -1L;
      waypointId = -1L;
    } else {
      // Show the track
      showTrack(track);
    }
  }

  /**
   * Returns true if the location is visible.
   *
   * @param location the location
   */
  private boolean isVisible(Location location) {
    if (location == null || mapView == null) {
      return false;
    }
    GeoPoint mapCenter = mapView.getMapCenter();
    int latitudeSpan = mapView.getLatitudeSpan();
    int longitudeSpan = mapView.getLongitudeSpan();
  
    /*
     * The bottom of the mapView is obscured by the zoom controls, subtract its
     * height from the visible area.
     */
    GeoPoint zoomControlBottom = mapView.getProjection().fromPixels(0, mapView.getHeight());
    GeoPoint zoomControlTop = mapView.getProjection().fromPixels(
        0, mapView.getHeight() - mapView.getZoomButtonsController().getZoomControls().getHeight());
    int zoomControlMargin = Math.abs(zoomControlTop.getLatitudeE6()
        - zoomControlBottom.getLatitudeE6());
    GeoRect geoRect = new GeoRect(mapCenter, latitudeSpan, longitudeSpan);
    geoRect.top += zoomControlMargin;
  
    GeoPoint geoPoint = LocationUtils.getGeoPoint(location);
    return geoRect.contains(geoPoint);
  }

  /**
   * Updates the current location and centers it if necessary.
   */
  private void updateCurrentLocation() {
    if (mapOverlay == null || mapView == null) {
      return;
    }

    mapOverlay.setMyLocation(currentLocation);
    mapView.postInvalidate();

    if (currentLocation != null && keepMyLocationVisible && !isVisible(currentLocation)) {
      GeoPoint geoPoint = LocationUtils.getGeoPoint(currentLocation);
      MapController mapController = mapView.getController();
      mapController.animateTo(geoPoint);
      if (zoomToMyLocation) {
        // Only zoom in the first time we show the location.
        zoomToMyLocation = false;
        if (mapView.getZoomLevel() < mapView.getMaxZoomLevel()) {
          mapController.setZoom(mapView.getMaxZoomLevel());
        }
      }
    }
  }

  /**
   * Shows the track.
   *
   * @param track the track
   */
  private void showTrack(Track track) {
    if (mapView == null || track == null || track.getNumberOfPoints() < 2) {
      return;
    }

    TripStatistics tripStatistics = track.getStatistics();
    int bottom = tripStatistics.getBottom();
    int left = tripStatistics.getLeft();
    int latitudeSpanE6 = tripStatistics.getTop() - bottom;
    int longitudeSpanE6 = tripStatistics.getRight() - left;
    if (latitudeSpanE6 > 0 && latitudeSpanE6 < 180E6 && longitudeSpanE6 > 0
        && longitudeSpanE6 < 360E6) {
      keepMyLocationVisible = false;
      GeoPoint center = new GeoPoint(bottom + latitudeSpanE6 / 2, left + longitudeSpanE6 / 2);
      if (LocationUtils.isValidGeoPoint(center)) {
        mapView.getController().setCenter(center);
        mapView.getController().zoomToSpan(latitudeSpanE6, longitudeSpanE6);
      }
    }
  }
}
