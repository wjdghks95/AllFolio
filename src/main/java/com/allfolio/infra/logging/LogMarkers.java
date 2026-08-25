package com.allfolio.infra.logging;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * 로그 마커 상수 모음. 인스턴스화하지 않는다.
 */
public final class LogMarkers {

    public static final Marker AUDIT = MarkerFactory.getMarker("AUDIT");

    private LogMarkers() {
    }
}
